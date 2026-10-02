"""Pinned runtime and provenance helpers for reproducible Stage 0 PoCs."""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOLCHAIN_PATH = ROOT / "scripts" / "poc" / "python-toolchain.lock.json"
RUN_SCHEMA_VERSION = "interviewmirror.mineru-run.v1.1.0"


class ProvenanceError(ValueError):
    """Raised when a result cannot be proven to belong to the current corpus/run."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def toolchain_lock() -> dict:
    return json.loads(TOOLCHAIN_PATH.read_text(encoding="utf-8"))


def generator_metadata(lock: dict | None = None) -> dict:
    lock = lock or toolchain_lock()
    return {key: lock[key] for key in ("python", "platform", "packages", "systemInputs")}


def check_runtime(check_packages: bool = True) -> None:
    lock = toolchain_lock()
    required = tuple(int(part) for part in lock["python"].split("."))
    current = sys.version_info[:3]
    if current != required:
        raise SystemExit(
            f"Pinned PoC runtime required: Python {lock['python']}; "
            f"current interpreter is {'.'.join(map(str, current))}. "
            "Use scripts/poc/.mineru/Scripts/python.exe via run_mineru.ps1."
        )
    if sys.platform != lock["platform"]:
        raise SystemExit(f"Pinned PoC platform required: {lock['platform']}; current platform is {sys.platform}.")
    if check_packages:
        from importlib import metadata

        mismatches = []
        for name, expected in lock["packages"].items():
            try:
                actual = metadata.version(name)
            except metadata.PackageNotFoundError:
                actual = "missing"
            if actual != expected:
                mismatches.append(f"{name}=={expected} required (found {actual})")
        if mismatches:
            raise SystemExit("Pinned PoC package versions required: " + "; ".join(mismatches))
    font = lock["systemInputs"]
    font_path = Path(font["cjkFontPath"])
    if not font_path.is_file() or sha256_file(font_path) != font["cjkFontSha256"]:
        raise SystemExit(f"Pinned corpus font missing or changed: {font_path}")


def validate_dataset(samples_dir: Path) -> tuple[dict, dict[str, dict]]:
    """Validate global/per-sample manifests and the bytes of every source file."""
    global_path = samples_dir / "manifest.json"
    if not global_path.is_file():
        raise ProvenanceError(f"dataset manifest missing: {global_path}")
    try:
        dataset = json.loads(global_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ProvenanceError(f"cannot read dataset manifest: {exc}") from exc
    if not isinstance(dataset, dict) or not dataset.get("datasetId") or not isinstance(dataset.get("samples"), list):
        raise ProvenanceError("dataset manifest has an invalid shape")
    lock = toolchain_lock()
    expected_generator = generator_metadata(lock)
    if dataset.get("generator") != expected_generator:
        raise ProvenanceError("dataset generator metadata does not match python-toolchain.lock.json")
    if dataset.get("schemaVersion") != "interviewmirror-samples.v1.1.0":
        raise ProvenanceError(f"unsupported dataset schema: {dataset.get('schemaVersion')!r}")

    ids: set[str] = set()
    filenames: set[str] = set()
    samples: dict[str, dict] = {}
    for entry in dataset["samples"]:
        sample_id = entry.get("sampleId")
        filename = entry.get("file")
        if (not isinstance(sample_id, str) or not sample_id or sample_id in ids
                or Path(sample_id).name != sample_id or "/" in sample_id or "\\" in sample_id):
            raise ProvenanceError(f"dataset contains an invalid or duplicate sampleId: {sample_id!r}")
        if not isinstance(filename, str) or Path(filename).name != filename or filename in filenames:
            raise ProvenanceError(f"dataset contains an invalid or duplicate filename: {filename!r}")
        ids.add(sample_id)
        filenames.add(filename)
        sample_path = samples_dir / f"{sample_id}.manifest.json"
        source_path = samples_dir / filename
        if not sample_path.is_file() or not source_path.is_file():
            raise ProvenanceError(f"{sample_id}: source file or per-sample manifest is missing")
        try:
            sample = json.loads(sample_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ProvenanceError(f"{sample_id}: cannot read per-sample manifest: {exc}") from exc
        expected_sha = entry.get("sha256")
        actual_sha = sha256_file(source_path)
        if sample.get("sampleId") != sample_id or sample.get("file") != filename:
            raise ProvenanceError(f"{sample_id}: per-sample manifest identity does not match global manifest")
        if sample.get("schemaVersion") != dataset.get("schemaVersion"):
            raise ProvenanceError(f"{sample_id}: per-sample schema version does not match global manifest")
        if not expected_sha or expected_sha != sample.get("sha256") or expected_sha != actual_sha:
            raise ProvenanceError(f"{sample_id}: input SHA-256 does not match both manifests and current bytes")
        sample["_sourceSha256"] = actual_sha
        samples[sample_id] = sample
    if dataset.get("sampleCount") != len(ids):
        raise ProvenanceError("dataset sampleCount does not match the manifest entries")
    return dataset, samples


def validate_run_provenance(samples_dir: Path, output_dir: Path) -> tuple[dict, dict, dict[str, dict]]:
    """Require an intact run-manifest bound to today's inputs and parsed outputs."""
    dataset, samples = validate_dataset(samples_dir)
    dataset_manifest_sha = sha256_file(samples_dir / "manifest.json")
    run_path = output_dir / "run-manifest.json"
    if not run_path.is_file():
        raise ProvenanceError(f"MinerU run-manifest missing: {run_path}")
    try:
        run = json.loads(run_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ProvenanceError(f"cannot read MinerU run-manifest: {exc}") from exc
    if run.get("schemaVersion") != RUN_SCHEMA_VERSION:
        raise ProvenanceError(
            f"unsupported or stale run-manifest schema {run.get('schemaVersion')!r}; "
            f"rerun MinerU to produce {RUN_SCHEMA_VERSION}"
        )
    if run.get("status") != "PASS":
        raise ProvenanceError(f"MinerU run did not pass: {run.get('status')!r}")
    if run.get("datasetId") != dataset.get("datasetId"):
        raise ProvenanceError("run-manifest datasetId does not match current dataset")
    if run.get("datasetManifestSha256") != dataset_manifest_sha:
        raise ProvenanceError("run-manifest dataset manifest SHA-256 is stale")
    if run.get("datasetSchemaVersion") != dataset.get("schemaVersion"):
        raise ProvenanceError("run-manifest dataset schema version does not match current dataset")
    if run.get("generator") != dataset.get("generator"):
        raise ProvenanceError("run-manifest generator metadata does not match current dataset")

    results = run.get("results")
    if not isinstance(results, list):
        raise ProvenanceError("run-manifest results must be an array")
    by_id: dict[str, dict] = {}
    for result in results:
        sample_id = result.get("sampleId")
        if not isinstance(sample_id, str) or sample_id in by_id:
            raise ProvenanceError(f"run-manifest has invalid or duplicate result sampleId: {sample_id!r}")
        by_id[sample_id] = result
    if set(by_id) != set(samples):
        missing = sorted(set(samples) - set(by_id))
        extra = sorted(set(by_id) - set(samples))
        raise ProvenanceError(f"run-manifest sample set mismatch; missing={missing}, extra={extra}")

    for sample_id, sample in samples.items():
        result = by_id[sample_id]
        expected_status = "PASS_DIRECT_TEXT" if sample["format"] in ("md", "txt") else "PASS"
        if result.get("status") != expected_status:
            raise ProvenanceError(f"{sample_id}: parse status is not {expected_status}")
        if result.get("file") != sample["file"] or result.get("sha256") != sample["_sourceSha256"]:
            raise ProvenanceError(f"{sample_id}: run input identity/hash does not match current source")
        if result.get("format") != sample.get("format") or result.get("category") != sample.get("category"):
            raise ProvenanceError(f"{sample_id}: run format/category do not match current sample manifest")
        output_name = f"{sample_id}.md"
        if result.get("output") != output_name:
            raise ProvenanceError(f"{sample_id}: run output path must be exactly {output_name!r}")
        output_path = output_dir / output_name
        if not output_path.is_file():
            raise ProvenanceError(f"{sample_id}: parsed output is missing")
        recorded_output_sha = result.get("outputSha256")
        if not recorded_output_sha or sha256_file(output_path) != recorded_output_sha:
            raise ProvenanceError(f"{sample_id}: parsed output SHA-256 does not match run-manifest")
    return dataset, run, samples
