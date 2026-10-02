#!/usr/bin/env python3
"""Integrity and coverage checks for the generated synthetic M0a corpus."""
import hashlib
import json
from collections import Counter
from pathlib import Path
from poc_runtime import check_runtime, validate_dataset

ROOT = Path(__file__).resolve().parents[2]
SAMPLES = ROOT / "data" / "poc" / "samples"


def main() -> int:
    check_runtime()
    errors = []
    global_path = SAMPLES / "manifest.json"
    if not global_path.exists():
        raise SystemExit("dataset manifest missing; run generate_samples.py first")
    try:
        global_manifest, sample_manifests = validate_dataset(SAMPLES)
    except ValueError as exc:
        print(json.dumps({"status": "FAIL", "errors": [str(exc)]}, ensure_ascii=False, indent=2))
        return 1
    entries = global_manifest["samples"]
    categories, formats, complex_ids, scanned_ids = Counter(), Counter(), set(), set()
    facts = 0
    for entry in entries:
        sample_id, name = entry["sampleId"], entry["file"]
        file_path = SAMPLES / name
        manifest_path = SAMPLES / f"{sample_id}.manifest.json"
        if not file_path.is_file() or not manifest_path.is_file():
            errors.append(f"{sample_id}: missing sample or per-file manifest")
            continue
        file_hash = hashlib.sha256(file_path.read_bytes()).hexdigest()
        sample = sample_manifests[sample_id]
        if file_hash != entry["sha256"] or file_hash != sample["sha256"]:
            errors.append(f"{sample_id}: SHA-256 mismatch")
        categories[sample["category"]] += 1
        formats[sample["format"]] += 1
        if set(sample["complexityTags"]) & {"table", "formula", "multi-column", "two-column", "multi-page", "cross-page"}:
            complex_ids.add(sample_id)
        if sample["scanned"]:
            scanned_ids.add(sample_id)
        gt = sample["groundTruth"]
        facts += sum(len(v) if isinstance(v, list) else len(v) for v in gt.values())
    if len(entries) < 20:
        errors.append(f"only {len(entries)} samples; minimum is 20")
    if categories["resume"] < 8 or categories["question_bank"] < 8 or categories["jd"] < 4:
        errors.append(f"category coverage too low: {dict(categories)}")
    if formats["pdf"] < 6 or formats["docx"] < 6 or formats["md"] < 3 or formats["txt"] < 3:
        errors.append(f"format coverage too low: {dict(formats)}")
    if len(scanned_ids) < 3:
        errors.append(f"only {len(scanned_ids)} scanned PDFs")
    if len(complex_ids) < 5:
        errors.append(f"only {len(complex_ids)} complex layout samples")
    result = {
        "datasetId": global_manifest["datasetId"],
        "datasetManifestSha256": hashlib.sha256(global_path.read_bytes()).hexdigest(),
        "generator": global_manifest["generator"],
        "sampleCount": len(entries), "categoryCounts": dict(categories), "formatCounts": dict(formats),
        "scannedPdfCount": len(scanned_ids), "complexSampleCount": len(complex_ids),
        "groundTruthAtomCount": facts, "status": "PASS" if not errors else "FAIL", "errors": errors,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
