#!/usr/bin/env python3
"""Upload all 30 Stage 2 labeled documents to the local app and score live MinerU results.

The runner logs identifiers and statuses only, deletes only records it created, and
keeps the full result bundle under ignored data/poc/results/phase2/.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import platform
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
import urllib.error
import urllib.request
import uuid
from http.cookiejar import CookieJar
from pathlib import Path
from statistics import median

ROOT = Path(__file__).resolve().parents[2]
POC = ROOT / "scripts" / "poc"
sys.path.insert(0, str(POC))
from score_parser_outputs import match_questions  # noqa: E402
from poc_runtime import check_runtime  # noqa: E402
from verify_eval_dataset import main as verify_eval_dataset  # noqa: E402

BASE = ROOT / "data" / "poc"
MANIFEST = BASE / "phase2" / "manifest.json"
RESULTS = BASE / "results" / "phase2"
MEDIA = {"pdf": "application/pdf", "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
         "txt": "text/plain", "md": "text/markdown", "markdown": "text/markdown"}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def norm(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", value.casefold())


def git_head() -> str:
    try:
        return subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"], capture_output=True,
                              check=True, text=True).stdout.strip()
    except Exception:
        return "unknown"


class LocalApi:
    def __init__(self, base_url: str, username: str, password: str, timeout: int):
        self.base = base_url.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
        self.timeout = timeout
        csrf = self.request("GET", "/api/v1/auth/csrf")
        self.csrf = csrf["data"]["token"]
        self.request("POST", "/api/v1/auth/login", {"identifier": username, "password": password})
        self.csrf = self.request("GET", "/api/v1/auth/csrf")["data"]["token"]

    def request(self, method: str, path: str, body: object | None = None,
                headers: dict[str, str] | None = None) -> dict:
        url = self.base + path
        request_headers = dict(headers or {})
        data = None
        if isinstance(body, bytes):
            data = body
        elif body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        if method not in {"GET", "HEAD", "OPTIONS"}:
            request_headers["X-XSRF-TOKEN"] = self.csrf
        request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                payload = response.read()
                return json.loads(payload) if payload else {}
        except urllib.error.HTTPError as error:
            content = error.read()
            try:
                payload = json.loads(content.decode("utf-8"))
                err = payload.get("error", {})
                message = err.get("message", "request failed")
                code = err.get("code", "HTTP_ERROR")
            except Exception:
                message, code = "request failed", "HTTP_ERROR"
            raise RuntimeError(f"{method} {path}: HTTP {error.code} {code}: {message}") from None

    def upload(self, endpoint: str, path: Path, content_type: str) -> dict:
        boundary = "----InterviewMirror" + uuid.uuid4().hex
        data = path.read_bytes()
        filename = path.name.replace('"', "_")
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
                f"Content-Type: {content_type}\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
        return self.request("POST", endpoint, body,
                            {"Content-Type": f"multipart/form-data; boundary={boundary}", "Content-Length": str(len(body))})


def resume_predictions(content: dict) -> dict[str, str]:
    personal = content.get("personalInfo") or {}
    education = content.get("education") or []
    projects = content.get("projects") or []
    metrics = content.get("metrics") or []
    return {
        "name": str(personal.get("name", "")),
        "education": " ".join(str(item.get("details") or item.get("institution") or "") for item in education),
        "skills": ", ".join(str(item) for item in content.get("skills", [])),
        "project": " ".join(str(item.get("name") or item.get("description") or "") for item in projects),
        "metric": " ".join(str(item) for item in metrics),
    }


def percentile(values: list[int], fraction: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def content_sha256(content: dict) -> str:
    serialized = json.dumps(content, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(serialized).hexdigest()


def main() -> int:
    check_runtime()
    verify_eval_dataset()
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="demo2")
    parser.add_argument("--password", default=os.environ.get("DEMO2_PASSWORD", "MirrorDemo2!"))
    parser.add_argument("--poll-seconds", type=float, default=1.5)
    parser.add_argument("--per-document-timeout", type=int, default=240)
    parser.add_argument("--api-timeout", type=int, default=25)
    args = parser.parse_args()
    if not args.password:
        raise SystemExit("Set DEMO2_PASSWORD in the environment before running the evaluation.")
    dataset = json.loads(MANIFEST.read_text(encoding="utf-8"))
    dataset_sha = sha(MANIFEST)
    RESULTS.mkdir(parents=True, exist_ok=True)
    api = LocalApi(args.base_url, args.username, args.password, args.api_timeout)
    created: list[tuple[str, str, str]] = []
    rows: list[dict] = []
    durations: list[int] = []
    resume_counts = {"tp": 0, "fp": 0, "fn": 0, "fields": 0}
    bank_counts = {"matched": 0, "predicted": 0, "expected": 0}
    failures = 0
    try:
        for sample in dataset["samples"]:
            file_path = BASE / sample["file"]
            endpoint = "/api/v1/resumes" if sample["category"] == "resume" else "/api/v1/question-banks"
            response = api.upload(endpoint, file_path, MEDIA[sample["format"]])
            document = response["data"]
            document_id = document["id"]
            task_id = document.get("parseTask", {}).get("id")
            created.append(("RESUME" if sample["category"] == "resume" else "QUESTION_BANK", document_id, sample["sampleId"]))
            started = time.monotonic()
            while document.get("status") in {"PENDING", "PROCESSING"}:
                if time.monotonic() - started > args.per_document_timeout:
                    break
                time.sleep(args.poll_seconds)
                document = api.request("GET", endpoint + "/" + document_id)["data"]
            status = document.get("status", "UNKNOWN")
            content = document.get("parsedContent") or {}
            task = document.get("parseTask") or {}
            duration = task.get("durationMs")
            if duration is not None:
                durations.append(int(duration))
            row = {"sampleId": sample["sampleId"], "category": sample["category"], "inputSha256": sample["sha256"],
                   "status": status, "parseTaskId": task_id, "retryCount": task.get("retryCount"),
                   "errorCode": task.get("errorCode"), "queuedAt": task.get("queuedAt"),
                   "startedAt": task.get("startedAt"), "completedAt": task.get("completedAt"),
                   "durationMs": duration,
                   "parsedContentSha256": content_sha256(content) if status == "PARSED" else None}
            if status != "PARSED":
                failures += 1
                if sample["category"] == "resume":
                    expected = sample["groundTruth"]["fields"]
                    resume_counts["fn"] += len(expected)
                    resume_counts["fields"] += len(expected)
                    row["fieldMatches"] = {key: False for key in expected}
                else:
                    expected_count = len(sample["groundTruth"]["questions"])
                    bank_counts["expected"] += expected_count
                row["matches"] = []
            elif sample["category"] == "resume":
                expected = sample["groundTruth"]["fields"]
                predicted = resume_predictions(content)
                tp = sum(bool(predicted.get(key)) and norm(predicted[key]) == norm(value) for key, value in expected.items())
                fp = sum(key not in expected or norm(expected[key]) != norm(value)
                         for key, value in predicted.items() if value)
                fn = len(expected) - tp
                resume_counts["tp"] += tp
                resume_counts["fp"] += fp
                resume_counts["fn"] += fn
                resume_counts["fields"] += len(expected)
                row.update({"truePositive": tp, "falsePositive": fp, "falseNegative": fn,
                            "fieldMatches": {key: bool(predicted.get(key)) and norm(predicted[key]) == norm(value)
                                            for key, value in expected.items()}})
            else:
                expected = sample["groundTruth"]["questions"]
                found = [str(item.get("stem", "")) for item in content.get("questions", [])]
                matches = match_questions(expected, found)
                bank_counts["matched"] += len(matches)
                bank_counts["predicted"] += len(found)
                bank_counts["expected"] += len(expected)
                row.update({"matched": len(matches), "predicted": len(found), "expected": len(expected),
                            "matches": [{"expectedIndex": left, "predictedIndex": right} for left, right in matches]})
            rows.append(row)
            print(f"sample={sample['sampleId']} status={status} durationMs={duration if duration is not None else 'missing'}")

        resume_precision = resume_counts["tp"] / max(1, resume_counts["tp"] + resume_counts["fp"])
        resume_recall = resume_counts["tp"] / max(1, resume_counts["tp"] + resume_counts["fn"])
        resume_f1 = 2 * resume_precision * resume_recall / max(1e-12, resume_precision + resume_recall)
        bank_recall = bank_counts["matched"] / max(1, bank_counts["expected"])
        bank_precision = bank_counts["matched"] / max(1, bank_counts["predicted"])
        p50, p95, maximum = percentile(durations, .50), percentile(durations, .95), max(durations) if durations else None
        result = {
            "schemaVersion": "interviewmirror.phase2-live-evaluation.v1.1",
            "runAt": datetime.now(timezone.utc).isoformat(),
            "datasetId": dataset["datasetId"], "datasetManifestSha256": dataset_sha,
            "gitHead": git_head(), "mineruVersion": importlib.metadata.version("mineru"),
            "runtime": {"python": sys.version, "platform": sys.platform},
            "hardware": {"processor": platform.processor(), "logicalCpuCount": os.cpu_count()},
            "parserConfig": {"pdfTier": "basic", "docxTier": "flash", "textMode": "direct-read",
                             "remoteParsing": False},
            "totalLabeledDocuments": len(dataset["samples"]),
            "resume": {**resume_counts, "precision": resume_precision, "recall": resume_recall, "f1": resume_f1},
            "questionBank": {**bank_counts, "precision": bank_precision, "recall": bank_recall},
            "parseDurationMs": {"completedSamples": len(durations), "p50": p50, "p95": p95, "max": maximum,
                                "allSamplesTerminal": len(rows) == len(dataset["samples"]) and failures == 0},
            "gates": {"documentsAtLeast30": len(rows) >= 30,
                      "resumeF1AtLeast90": resume_f1 >= .9,
                      "questionRecallAtLeast90": bank_recall >= .9,
                      "parseP95AtMost60Seconds": p95 is not None and p95 <= 60000 and failures == 0,
                      "allDocumentsParsed": failures == 0},
            "samples": rows,
        }
        out = RESULTS / "live-evaluation.json"
        out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"resumeF1": round(resume_f1, 4), "questionRecall": round(bank_recall, 4),
                          "durationMs": result["parseDurationMs"], "gates": result["gates"], "resultPath": str(out)},
                         ensure_ascii=False, indent=2))
    finally:
        cleanup_errors = []
        for type_name, document_id, sample_id in reversed(created):
            route = "/api/v1/resumes" if type_name == "RESUME" else "/api/v1/question-banks"
            try:
                api.request("DELETE", route + "/" + document_id)
            except Exception as error:
                cleanup_errors.append({"sampleId": sample_id, "error": str(error)[:240]})
        if cleanup_errors:
            cleanup = RESULTS / "cleanup-errors.json"
            cleanup.write_text(json.dumps(cleanup_errors, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"cleanup=FAILED count={len(cleanup_errors)} details={cleanup}", file=sys.stderr)
    return 0 if all(result["gates"].values()) and not cleanup_errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
