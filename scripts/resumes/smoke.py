"""Disposable PostgreSQL/Redis/MinIO + packaged backend/OCR acceptance.

Build interviewmirror/resume-validation:local first. Requires Docker, Pillow and reportlab.
Only randomly named containers/networks created by this script are removed.
The HTTP model stub contains synthetic data and makes no external model calls.
"""
from __future__ import annotations

import http.cookiejar
import http.server
import io
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
IMAGE = os.environ.get("RESUME_SMOKE_IMAGE", "interviewmirror/resume-validation:local")
PREFIX = "im-resume-test-" + uuid.uuid4().hex[:10]
CREATED: list[str] = []
MODEL_MODE = "ok"


def docker(*args: str, check: bool = True) -> str:
    result = subprocess.run(["docker", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode:
        raise RuntimeError("Docker operation failed: " + " ".join(args[:3]) + "\n" + result.stderr[-1500:])
    return result.stdout.strip()


def run(name: str, image: str, *args: str, env: dict[str, str] | None = None, port: int | None = None) -> str:
    full = PREFIX + "-" + name
    command = ["run", "-d", "--name", full, "--network", PREFIX]
    if port:
        command += ["-p", f"127.0.0.1::{port}"]
    for key, value in (env or {}).items():
        command += ["-e", f"{key}={value}"]
    CREATED.append(full)
    docker(*command, image, *args)
    return full


def exposed(name: str, port: int) -> str:
    return docker("port", name, str(port)).splitlines()[0].rsplit(":", 1)[1]


def wait_until(action, timeout: int = 150):
    deadline = time.monotonic() + timeout
    last: BaseException | None = None
    while time.monotonic() < deadline:
        try:
            result = action()
            if result:
                return result
        except (urllib.error.URLError, ConnectionError, RuntimeError) as error:
            last = error
        time.sleep(1)
    raise AssertionError(f"Timed out after {timeout}s waiting for acceptance condition; last error: {last!r}")


class Model(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        request = self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode()
        if MODEL_MODE == "fail":
            self.send_response(503)
            self.end_headers()
            return
        content = {
            "scoreDetail": {"contentScore": 12, "structureScore": 12, "skillMatchScore": 16, "expressionScore": 8, "projectScore": 32},
            "strengths": [{"text": "正文列出了技术实践。", "evidenceIds": ["E001"]}],
            "suggestions": [{"category": "项目", "priority": "高", "type": "SUPPLEMENT", "issue": "未提供测试指标", "recommendation": "建议补充真实测试结果。", "evidenceIds": []}],
        }
        if "phase3.interview-plan.v1" in request:
            content = {"questions": [{"stem": f"请介绍项目中的技术方案和验证方式 {n}", "category": "工程", "rationale": "依据简历项目提问"} for n in range(1, 7)]}
        payload = json.dumps({"id": "synthetic-resume", "object": "chat.completion", "created": 1, "model": "synthetic",
                              "choices": [{"index": 0, "message": {"role": "assistant", "content": json.dumps(content, ensure_ascii=False)}, "finish_reason": "stop"}],
                              "usage": {"prompt_tokens": 100, "completion_tokens": 100, "total_tokens": 200}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


class Client:
    def __init__(self, base: str):
        self.base = base
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = ""

    def request(self, path: str, method: str = "GET", body=None, content_type="application/json", expected=200):
        data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
        headers = {"Content-Type": content_type}
        if method != "GET":
            headers["X-XSRF-TOKEN"] = self.csrf
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            response = self.opener.open(req, timeout=150)
        except urllib.error.HTTPError as error:
            response = error
        payload = response.read()
        assert response.status == expected, (path, response.status, payload[:500])
        if "json" in response.headers.get("Content-Type", ""):
            return json.loads(payload).get("data")
        return payload

    def login(self, name):
        self.csrf = self.request("/api/v1/auth/csrf")["token"]
        self.request("/api/v1/auth/login", "POST", {"identifier": name, "password": "ResumeSmokeOnly!"})
        self.csrf = self.request("/api/v1/auth/csrf")["token"]

    def upload(self, filename: str, data: bytes, mime: str, expected=200):
        boundary = "resume-" + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: {mime}\r\n\r\n'.encode()
                + data + f"\r\n--{boundary}--\r\n".encode())
        return self.request("/api/v1/resumes/upload", "POST", body, "multipart/form-data; boundary=" + boundary, expected)


def scanned_pdf() -> bytes:
    from PIL import Image, ImageDraw, ImageFont
    from reportlab.pdfgen import canvas
    from reportlab.lib.utils import ImageReader
    image = Image.new("RGB", (1800, 2200), "white")
    draw = ImageDraw.Draw(image)
    candidates = [Path("C:/Windows/Fonts/msyh.ttc"), Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")]
    font = ImageFont.truetype(str(next(p for p in candidates if p.exists())), 52)
    for index, line in enumerate(["张三 / Resume", "技能 Skills: Java Redis PostgreSQL", "项目 Project: API development", "工作 Experience: backend engineer"]):
        draw.text((120, 150 + index * 150), line, font=font, fill="black")
    output = io.BytesIO()
    pdf = canvas.Canvas(output, pagesize=(600, 733))
    pdf.drawImage(ImageReader(image), 0, 0, 600, 733)
    pdf.showPage(); pdf.save()
    return output.getvalue()


def main():
    global MODEL_MODE
    server = http.server.ThreadingHTTPServer(("0.0.0.0", 0), Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    network_created = False
    app = ""
    try:
        docker("network", "create", PREFIX); network_created = True
        db = run("postgres", "postgres:17.6-alpine", env={"POSTGRES_DB": "resume_smoke", "POSTGRES_USER": "resume_smoke", "POSTGRES_PASSWORD": "ResumeSmokeDatabaseOnly!"})
        redis = run("redis", "redis:7.4-alpine")
        minio = run("minio", "interviewmirror/minio-local:RELEASE.2025-10-15T17-29-55Z", env={"MINIO_ROOT_USER": "resume-smoke", "MINIO_ROOT_PASSWORD": "ResumeSmokeStorageOnly!"})
        wait_until(lambda: "accepting connections" in docker("exec", db, "pg_isready", "-U", "resume_smoke", check=False))
        app = run("backend", IMAGE, env={
            "SPRING_PROFILES_ACTIVE": "local",
            "JAVA_TOOL_OPTIONS": "-Xmx768m",
            "DATABASE_URL": f"jdbc:postgresql://{db}:5432/resume_smoke", "DATABASE_USER": "resume_smoke", "DATABASE_PASSWORD": "ResumeSmokeDatabaseOnly!",
            "REDIS_HOST": redis, "S3_ENDPOINT": f"http://{minio}:9000", "S3_ACCESS_KEY": "resume-smoke", "S3_SECRET_KEY": "ResumeSmokeStorageOnly!", "S3_BUCKET": "resume-smoke",
            "DEMO1_PASSWORD": "ResumeSmokeOnly!", "DEMO2_PASSWORD": "ResumeSmokeOnly!", "INTERVIEW_MODEL_ENABLED": "true", "INTERVIEW_MODEL_API_KEY": "synthetic-only",
            "INTERVIEW_MODEL_BASE_URL": f"http://host.docker.internal:{server.server_port}", "INTERVIEW_MODEL_ID": "synthetic", "REPORT_WORKER_ENABLED": "false",
            "PARSE_WORKER_ENABLED": "false", "RESUME_UPLOAD_GLOBAL_RATE": "100", "RESUME_UPLOAD_IP_RATE": "100", "RESUME_UPLOAD_USER_RATE": "100",
        }, port=8080)
        base = "http://127.0.0.1:" + exposed(app, 8080)
        wait_until(lambda: json.load(urllib.request.urlopen(base + "/actuator/health", timeout=3))["status"] == "UP", 360)
        wait_until(lambda: docker("exec", db, "psql", "-U", "resume_smoke", "-d", "resume_smoke", "-Atc", "SELECT COUNT(*) FROM app_users") == "2")
        print("Ready: isolated backend, PostgreSQL, Redis and MinIO", flush=True)
        a, b = Client(base), Client(base)
        a.login("demo1"); b.login("demo2")
        text = "姓名：张三\n技能：Java Redis PostgreSQL\n项目：API development\n".encode()
        first = a.upload("resume.txt", text, "text/plain")
        rid = first["id"]
        assert first["usableForInterview"] and "summary" not in first
        detail_path = f"/api/v1/resumes/{rid}/detail"
        detail = wait_until(lambda: (d if d["resume"]["analyzeStatus"] == "COMPLETED" else None) if (d := a.request(detail_path)) else None)
        analysis = detail["analyses"]["items"][0]
        assert analysis["overallScore"] == 80 and "summary" not in analysis
        evidence = analysis["strengths"][0]["evidence"][0]
        assert evidence["quote"] in first["resumeText"] and evidence["sourceId"] == rid
        print("PASS: synchronous extraction, grading and backend evidence hydration", flush=True)
        docker("exec", redis, "redis-cli", "XADD", "interviewmirror:resumes", "*", "resumeId", rid, "retryCount", "0")
        assert a.upload("resume.txt", text, "text/plain")["accessCount"] == 2
        assert b.upload("resume.txt", text, "text/plain")["id"] != rid
        for suffix in ("detail", "preview", "file", "export"):
            b.request(f"/api/v1/resumes/{rid}/{suffix}", expected=404)
        assert a.request(f"/api/v1/resumes/{rid}/export").startswith(b"%PDF")
        scanned = a.upload("scan.pdf", scanned_pdf(), "application/pdf")
        assert "Java" in scanned["resumeText"] and "API" in scanned["resumeText"]
        # The parse JVM must keep Tika/Tesseract scratch files inside its job directory. Both parses above
        # needed OCR, so any leftover under /tmp means the child temp-dir isolation is broken.
        leftovers = docker("exec", app, "sh", "-c",
                           "ls -d /tmp/interviewmirror-resume-* /tmp/apache-tika-* 2>/dev/null || true")
        assert not leftovers, "parse scratch files left in container /tmp:\n" + leftovers
        word = a.upload("resume.doc", (ROOT / "backend/src/test/resources/resumes/word97.doc").read_bytes(), "application/msword")
        assert word["resumeText"].strip()
        print("PASS: scanned PDF OCR, Word97 extraction and contained parser scratch space", flush=True)
        a.upload("fake.pdf", b"this is not a PDF", "application/pdf", expected=400)
        a.upload("empty.txt", b"   \n", "text/plain", expected=400)
        a.upload("large.txt", b"x" * (10 * 1024 * 1024 + 1), "text/plain", expected=413)
        MODEL_MODE = "fail"
        a.request(f"/api/v1/resumes/{rid}/reanalyze", "POST")
        failed = wait_until(lambda: (d if d["resume"]["analyzeStatus"] == "FAILED" else None) if (d := a.request(detail_path)) else None, 120)
        assert failed["analyses"]["total"] == 1
        print("PASS: failed grading preserves history and reaches bounded retry limit", flush=True)
        assert a.upload("resume.txt", text, "text/plain")["analyzeStatus"] == "FAILED"
        MODEL_MODE = "ok"
        a.request(f"/api/v1/resumes/{rid}/reanalyze", "POST")
        wait_until(lambda: a.request(detail_path)["analyses"]["total"] == 2)
        interview = a.request("/api/v1/interviews", "POST", {"schemaVersion": "1.1.0", "clientRequestId": "resume-smoke", "mode": "COMPREHENSIVE", "resumeId": rid, "locale": "zh-CN", "modelDataConsent": True})
        iid = interview["id"]
        assert a.request(f"/api/v1/interviews/{iid}/start", "POST")["status"] == "RUNNING"
        assert docker("exec", db, "psql", "-U", "resume_smoke", "-d", "resume_smoke", "-Atc", f"SELECT COUNT(*) FROM lg4jthread WHERE thread_name='{iid}'") == "1"
        print("PASS: full-text interview snapshot and fenced graph checkpoints", flush=True)
        a.request(f"/api/v1/interviews/{iid}/end", "POST", {"clientRequestId": "resume-smoke-end"})
        report_id = str(uuid.uuid4())
        report_content = json.dumps({"title": "Synthetic interview", "mode": "COMPREHENSIVE", "summary": {"overallScore": 80, "overallScoreStatus": "ASSESSED", "overallReview": "Synthetic answer evidence."}, "scores": {}, "turns": [], "strengths": [], "risks": [], "recommendations": [], "learningPath": []}).replace("'", "''")
        docker("exec", db, "psql", "-U", "resume_smoke", "-d", "resume_smoke", "-c",
               f"INSERT INTO interview_reports(id,interview_id,owner_id,schema_version,status,content,source_snapshot,completed_at,generation_duration_ms) SELECT '{report_id}',id,owner_id,'1.7','READY','{report_content}','{{}}',CURRENT_TIMESTAMP,1 FROM interviews WHERE id='{iid}'")
        assert a.request(f"/api/v1/reports/{report_id}/pdf").startswith(b"%PDF")
        report_key = docker("exec", db, "psql", "-U", "resume_smoke", "-d", "resume_smoke", "-Atc", f"SELECT object_key FROM report_pdf_artifacts WHERE report_id='{report_id}'")
        original_key = docker("exec", db, "psql", "-U", "resume_smoke", "-d", "resume_smoke", "-Atc", f"SELECT storage_key FROM resumes WHERE id='{rid}'")
        for key in (report_key, original_key):
            docker("exec", minio, "sh", "-c", 'test -f "$1"', "sh", "/data/resume-smoke/" + key + "/xl.meta")
        docker("stop", minio)
        a.request(f"/api/v1/resumes/{rid}", "DELETE", expected=202)
        wait_until(lambda: a.request(detail_path)["resume"]["deleteStatus"] == "DELETE_FAILED", 45)
        docker("start", minio)
        a.request(f"/api/v1/resumes/{rid}", "DELETE", expected=202)
        wait_until(lambda: docker("exec", db, "psql", "-U", "resume_smoke", "-d", "resume_smoke", "-Atc", f"SELECT COUNT(*) FROM resumes WHERE id='{rid}'") == "0")
        for key in (report_key, original_key):
            docker("exec", minio, "sh", "-c", 'test ! -f "$1"', "sh", "/data/resume-smoke/" + key + "/xl.meta")
        assert docker("exec", db, "psql", "-U", "resume_smoke", "-d", "resume_smoke", "-Atc", f"SELECT COUNT(*) FROM lg4jthread WHERE thread_name='{iid}'") == "0"
        a.request("/api/v1/interviews/" + interview["id"], expected=404)
        a.request(detail_path, expected=404)
        docker("exec", redis, "redis-cli", "SET", "resume:rate:global", "100", "EX", "60")
        a.upload("limited.txt", text, "text/plain", expected=429)
        print("PASS: packaged Tika/OCR, PostgreSQL, Redis, MinIO, evidence hydration, isolation, retries, PDF and deletion")
    except Exception:
        if app:
            docker("exec", app, "kill", "-3", "1", check=False)
            time.sleep(1)
            log_path = ROOT / "backend" / "target" / "resume-smoke-backend.log"
            log_path.parent.mkdir(parents=True, exist_ok=True)
            log_path.write_text(docker("logs", app, check=False), encoding="utf-8")
            print("Backend diagnostic log:", log_path)
        raise
    finally:
        server.shutdown(); server.server_close()
        for container in reversed(CREATED):
            docker("rm", "-f", "-v", container, check=False)
        if network_created:
            docker("network", "rm", PREFIX, check=False)


if __name__ == "__main__":
    main()
