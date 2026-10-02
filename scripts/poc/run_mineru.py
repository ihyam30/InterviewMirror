#!/usr/bin/env python3
"""Run MinerU Kit's local parser on binary fixtures; never select --remote."""
from __future__ import annotations
import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from poc_runtime import (RUN_SCHEMA_VERSION, check_runtime, sha256_file,
                         validate_dataset)

ROOT = Path(__file__).resolve().parents[2]
SAMPLES = ROOT / "data" / "poc" / "samples"
OUTPUTS = ROOT / "data" / "poc" / "results" / "mineru"

try:
    import psutil
except ImportError:  # resource collection stays optional for portability
    psutil = None


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_metadata() -> tuple[str, bool | None]:
    try:
        head = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"], capture_output=True,
                              text=True, check=True).stdout.strip()
        dirty = bool(subprocess.run(["git", "-C", str(ROOT), "status", "--porcelain"], capture_output=True,
                                    text=True, check=True).stdout.strip())
        return head, dirty
    except (OSError, subprocess.CalledProcessError):
        return "unknown", None


def version(executable: str) -> str:
    try:
        return importlib.metadata.version("mineru")
    except importlib.metadata.PackageNotFoundError:
        try:
            result = subprocess.run([executable, "--version"], capture_output=True, text=True, timeout=20)
            return (result.stdout or result.stderr).strip()[:300] or "unknown"
        except Exception:
            return "unknown"


def run_and_measure(command: list[str], env: dict[str, str], timeout: int) -> tuple[int, str, str, float | None]:
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                encoding="utf-8", errors="replace", env=env)
    peak = [0]
    stop = threading.Event()

    def sample_memory() -> None:
        if psutil is None:
            return
        try:
            parent = psutil.Process(process.pid)
            while not stop.is_set():
                try:
                    processes = [parent, *parent.children(recursive=True)]
                    peak[0] = max(peak[0], sum(p.memory_info().rss for p in processes))
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
                stop.wait(.1)
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            return

    monitor = threading.Thread(target=sample_memory, daemon=True)
    monitor.start()
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        stdout, stderr = process.communicate()
        raise subprocess.TimeoutExpired(command, timeout, output=stdout, stderr=stderr)
    finally:
        stop.set()
        monitor.join(timeout=1)
    return process.returncode, stdout, stderr, round(peak[0] / (1024 * 1024), 2) if psutil else None


def main() -> int:
    check_runtime()
    parser = argparse.ArgumentParser()
    parser.add_argument("--mineru", default="mineru-kit", help="MinerU Kit CLI executable")
    parser.add_argument("--tier", choices=("basic", "standard"), default="basic")
    parser.add_argument("--output-dir", type=Path, default=OUTPUTS)
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    manifest, samples = validate_dataset(SAMPLES)
    git_head, git_dirty = git_metadata()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    executable = shutil.which(args.mineru)
    results = []
    if executable is None:
        report = {
            "schemaVersion": RUN_SCHEMA_VERSION,
            "status": "BLOCKED", "reason": f"MinerU Kit CLI not found: {args.mineru}",
            "mineruVersion": "not-installed", "datasetId": manifest["datasetId"],
            "datasetManifestSha256": sha256(SAMPLES / "manifest.json"),
            "datasetSchemaVersion": manifest["schemaVersion"], "generator": manifest["generator"],
            "gitHead": git_head, "gitDirty": git_dirty, "promptVersion": None,
            "python": sys.version, "platform": platform.platform(), "results": [],
        }
        (args.output_dir / "run-manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 2
    for entry in manifest["samples"]:
        sample = samples[entry["sampleId"]]
        input_path = SAMPLES / entry["file"]
        if sha256_file(input_path) != entry["sha256"]:
            raise SystemExit(f"{entry['sampleId']}: input changed after dataset validation; rerun generation/verification")
        output_path = args.output_dir / f"{entry['sampleId']}.md"
        # MinerU Kit expects a file path for a new output, but an existing output
        # path can be interpreted as a directory. Clear only this script-owned file.
        output_path.unlink(missing_ok=True)
        start = time.perf_counter()
        if sample["format"] in ("md", "txt"):
            output_path.write_text(input_path.read_text(encoding="utf-8"), encoding="utf-8")
            status, stderr, command, peak_rss = "PASS_DIRECT_TEXT", "", ["direct-read"], None
        else:
            parse_tier = "flash" if sample["format"] == "docx" else args.tier
            command = [executable, "parse", str(input_path), "--output", str(output_path),
                       "--format", "markdown", "--tier", parse_tier]
            if sample["format"] == "pdf":
                command.extend(["--pages", "all"])
            try:
                env = os.environ.copy()
                env["MINERU_HOME"] = str(ROOT / "scripts" / "poc" / ".mineru" / "home")
                env["MINERU_MODEL_SOURCE"] = "local"
                temp_dir = ROOT / "scripts" / "poc" / ".mineru" / "tmp"
                temp_dir.mkdir(parents=True, exist_ok=True)
                env["TMP"] = env["TEMP"] = env["TMPDIR"] = str(temp_dir)
                return_code, stdout, stderr_full, peak_rss = run_and_measure(command, env, args.timeout)
                status = "PASS" if return_code == 0 and output_path.exists() else "FAIL"
                stderr = (stderr_full or stdout)[-2000:]
            except subprocess.TimeoutExpired as exc:
                status, stderr, peak_rss = "TIMEOUT", str(exc)[-1000:], None
            except Exception as exc:
                status, stderr, peak_rss = "ERROR", repr(exc), None
        results.append({
            "sampleId": entry["sampleId"], "file": entry["file"], "sha256": entry["sha256"],
            "format": sample["format"], "category": sample["category"], "tags": sample["complexityTags"],
            "status": status, "elapsedMs": round((time.perf_counter() - start) * 1000, 2),
            "peakRssMb": peak_rss, "output": output_path.name if output_path.exists() else None,
            "outputSha256": sha256_file(output_path) if output_path.is_file() else None,
            "error": stderr, "command": command, "localTier": ("flash" if sample["format"] == "docx" else args.tier),
        })
    report = {
        "schemaVersion": RUN_SCHEMA_VERSION,
        "status": "PASS" if results and all(r["status"].startswith("PASS") for r in results) else "FAIL",
        "mineruVersion": version(executable), "tier": f"{args.tier} for PDF/image; flash for DOCX; direct read for MD/TXT",
        "datasetId": manifest["datasetId"], "datasetManifestSha256": sha256(SAMPLES / "manifest.json"),
        "datasetSchemaVersion": manifest["schemaVersion"], "generator": manifest["generator"],
        "gitHead": git_head, "gitDirty": git_dirty, "python": sys.version, "platform": platform.platform(),
        "startedAtEpoch": time.time() - sum(r["elapsedMs"] for r in results) / 1000,
        "localApiCostCny": 0, "modelDownloadCostCny": 0,
        "results": results,
    }
    (args.output_dir / "run-manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "mineruVersion": report["mineruVersion"], "parsed": len(results),
                      "failed": sum(not r["status"].startswith("PASS") for r in results),
                      "manifest": str(args.output_dir / "run-manifest.json")}, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
