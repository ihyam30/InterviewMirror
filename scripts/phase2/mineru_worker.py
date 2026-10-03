#!/usr/bin/env python3
"""Authenticated loopback bridge to the pinned local MinerU Kit CLI."""
from __future__ import annotations

import base64
import binascii
import hmac
import importlib.metadata
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote_plus

MAX_FILE_BYTES = 20 * 1024 * 1024
MAX_EXPANDED_DOCX_BYTES = 128 * 1024 * 1024
MAX_DOCX_ENTRIES = 2_000
MAX_OUTPUT_BYTES = 5 * 1024 * 1024
SUPPORTED = {".pdf", ".docx", ".txt", ".md", ".markdown"}
PARSE_LOCK = threading.BoundedSemaphore(1)


def safe_filename(header: str) -> str:
    try:
        decoded = base64.urlsafe_b64decode(header + "=" * (-len(header) % 4)).decode("utf-8")
    except (ValueError, UnicodeDecodeError, binascii.Error) as error:
        raise WorkerError(400, "INVALID_DOCUMENT", "文件名无效") from error
    name = decoded.replace("\\", "/").split("/")[-1].strip()
    name = re.sub(r"[\x00-\x1f\x7f]", "_", name)
    if not name or len(name) > 255 or Path(name).suffix.lower() not in SUPPORTED:
        raise WorkerError(415, "UNSUPPORTED_DOCUMENT", "文件格式暂不支持")
    return name


def validate_docx(path: Path) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            members = archive.infolist()
            if len(members) > MAX_DOCX_ENTRIES or sum(item.file_size for item in members) > MAX_EXPANDED_DOCX_BYTES:
                raise WorkerError(400, "INVALID_DOCUMENT", "DOCX 文件结构异常")
            if any(".." in Path(item.filename).parts or item.filename.startswith(("/", "\\")) for item in members):
                raise WorkerError(400, "INVALID_DOCUMENT", "DOCX 文件结构异常")
            if "[Content_Types].xml" not in {item.filename for item in members}:
                raise WorkerError(400, "INVALID_DOCUMENT", "DOCX 文件结构异常")
            if archive.testzip() is not None:
                raise WorkerError(400, "INVALID_DOCUMENT", "DOCX 文件无法读取")
    except WorkerError:
        raise
    except (OSError, zipfile.BadZipFile) as error:
        raise WorkerError(400, "INVALID_DOCUMENT", "DOCX 文件无法读取") from error


def validate_pdf(path: Path) -> None:
    try:
        from pypdf import PdfReader

        reader = PdfReader(str(path), strict=False)
        if reader.is_encrypted:
            raise WorkerError(400, "ENCRYPTED_PDF", "PDF 已加密")
        if len(reader.pages) > 50:
            raise WorkerError(413, "PDF_PAGE_LIMIT", "PDF 页数超过 50 页")
        if len(reader.pages) == 0:
            raise WorkerError(400, "INVALID_DOCUMENT", "PDF 没有可读取页面")
    except WorkerError:
        raise
    except Exception as error:  # parser errors vary by PDF implementation
        raise WorkerError(400, "INVALID_DOCUMENT", "PDF 文件无法读取") from error


def parse_local_file(path: Path, filename: str, timeout: int) -> tuple[str, str, int]:
    extension = path.suffix.lower()
    if extension in {".txt", ".md", ".markdown"}:
        raw = path.read_bytes()
        if b"\x00" in raw[:4096]:
            raise WorkerError(400, "INVALID_DOCUMENT", "文本文件编码无法识别")
        text = raw.decode("utf-8-sig", errors="replace")
        replacement_count = text.count("\ufffd")
        if replacement_count > max(4, len(text) // 100):
            raise WorkerError(400, "INVALID_DOCUMENT", "文本文件编码无法识别")
        return text, "direct-text", 0

    if extension == ".pdf":
        validate_pdf(path)
        tier = "basic"
    else:
        validate_docx(path)
        tier = "flash"

    executable = os.environ.get("MINERU_EXECUTABLE") or shutil.which("mineru-kit")
    if not executable or not Path(executable).is_file():
        raise WorkerError(503, "PARSER_UNAVAILABLE", "本地 MinerU 未安装或不可用")
    output_fd, output_name = tempfile.mkstemp(prefix="interviewmirror-mineru-", suffix=".md")
    os.close(output_fd)
    output = Path(output_name)
    try:
        # Some Windows sandboxes deny access to newly-created temporary directories.
        # A unique file directly in the OS temp root is sufficient for this CLI call.
        output.unlink()
        command = [executable, "parse", str(path), "--output", str(output),
                   "--format", "markdown", "--tier", tier]
        if extension == ".pdf":
            command.extend(["--pages", "all"])
        start = time.perf_counter()
        try:
            result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                    errors="replace", timeout=timeout, check=False)
        except subprocess.TimeoutExpired as error:
            raise WorkerError(504, "PARSER_TIMEOUT", "文档解析超时") from error
        except OSError as error:
            raise WorkerError(503, "PARSER_UNAVAILABLE", "本地 MinerU 无法启动") from error
        elapsed = int((time.perf_counter() - start) * 1000)
        # Never return MinerU's diagnostic text: it may contain excerpts from the file.
        if result.returncode != 0 or not output.is_file():
            raise WorkerError(502, "PARSER_FAILED", "文档解析失败")
        if output.stat().st_size > MAX_OUTPUT_BYTES:
            raise WorkerError(413, "PARSED_OUTPUT_TOO_LARGE", "解析结果过大")
        markdown = output.read_text(encoding="utf-8", errors="replace")
        return markdown, importlib.metadata.version("mineru"), elapsed
    finally:
        output.unlink(missing_ok=True)


class WorkerError(Exception):
    def __init__(self, status: int, code: str, safe_message: str):
        super().__init__(safe_message)
        self.status = status
        self.code = code
        self.safe_message = safe_message


class Handler(BaseHTTPRequestHandler):
    server_version = "InterviewMirrorMinerU/1"

    def log_message(self, format: str, *args) -> None:
        # Do not log path, header values, filenames, or document body.
        print(f"client={self.client_address[0]} request=mineru status={getattr(self, '_response_status', 0)}")

    def send_json(self, status: int, payload: dict, code: str | None = None) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self._response_status = status
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if code:
            self.send_header("X-Error-Code", code)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path != "/healthz":
            self.send_json(404, {"error": "not_found"})
            return
        try:
            version = importlib.metadata.version("mineru")
            self.send_json(200, {"status": "ok", "mineruVersion": version})
        except importlib.metadata.PackageNotFoundError:
            self.send_json(503, {"status": "unavailable"})

    def do_POST(self) -> None:
        if self.path != "/parse":
            self.send_json(404, {"error": "not_found"})
            return
        expected = os.environ.get("MINERU_WORKER_TOKEN", "").encode("utf-8")
        supplied = self.headers.get("X-Worker-Token", "").encode("utf-8")
        if len(expected) < 32 or not hmac.compare_digest(expected, supplied):
            self.send_json(401, {"error": "unauthorized"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > MAX_FILE_BYTES:
                raise WorkerError(413, "FILE_TOO_LARGE", "文件为空或超过 20 MiB")
            filename = safe_filename(self.headers.get("X-Document-Filename-B64", ""))
            if not PARSE_LOCK.acquire(blocking=False):
                raise WorkerError(429, "PARSER_BUSY", "解析服务正在处理其他文件")
            try:
                data = self.rfile.read(length)
                if len(data) != length:
                    raise WorkerError(400, "INVALID_DOCUMENT", "上传内容不完整")
                fd, source_name = tempfile.mkstemp(
                    prefix="interviewmirror-upload-", suffix=Path(filename).suffix.lower()
                )
                path = Path(source_name)
                try:
                    with os.fdopen(fd, "wb") as source_file:
                        source_file.write(data)
                    markdown, version, elapsed = parse_local_file(path, filename, int(os.environ.get("MINERU_TIMEOUT_SECONDS", "120")))
                finally:
                    path.unlink(missing_ok=True)
                encoded = markdown.encode("utf-8")
                if len(encoded) > MAX_OUTPUT_BYTES:
                    raise WorkerError(413, "PARSED_OUTPUT_TOO_LARGE", "解析结果过大")
                self._response_status = 200
                self.send_response(200)
                self.send_header("Content-Type", "text/markdown; charset=utf-8")
                self.send_header("Content-Length", str(len(encoded)))
                self.send_header("X-MinerU-Version", version)
                self.send_header("X-Parser-Duration-Ms", str(elapsed))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(encoded)
            finally:
                PARSE_LOCK.release()
        except WorkerError as error:
            self.send_json(error.status, {"error": error.safe_message}, error.code)
        except (BrokenPipeError, ConnectionResetError):
            self._response_status = 499
        except Exception as error:
            # Exception messages may include local paths or parser output; retain only its type.
            print(f"request=mineru status=500 failureType={type(error).__name__}")
            self.send_json(500, {"error": "解析服务内部错误"}, "PARSER_FAILED")


def main() -> None:
    token = os.environ.get("MINERU_WORKER_TOKEN", "")
    if len(token) < 32:
        raise SystemExit("Set MINERU_WORKER_TOKEN to a random value of at least 32 characters.")
    address = os.environ.get("MINERU_WORKER_BIND", "127.0.0.1")
    port = int(os.environ.get("MINERU_WORKER_PORT", "8765"))
    server = ThreadingHTTPServer((address, port), Handler)
    server.daemon_threads = True
    print(f"MinerU worker listening on {address}:{port}; document contents are not logged.")
    server.serve_forever(poll_interval=0.5)


if __name__ == "__main__":
    main()
