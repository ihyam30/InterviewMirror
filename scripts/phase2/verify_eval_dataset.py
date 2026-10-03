#!/usr/bin/env python3
"""Verify Stage 2 annotation bindings, coverage, and deterministic sample hashes."""
from __future__ import annotations
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "data" / "poc"
MANIFEST = BASE / "phase2" / "manifest.json"
ANNOTATIONS = BASE / "phase2" / "annotations"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    dataset = json.loads(MANIFEST.read_text(encoding="utf-8"))
    samples = dataset["samples"]
    ids = [sample["sampleId"] for sample in samples]
    if len(samples) < 30 or len(ids) != len(set(ids)):
        raise SystemExit("FAIL: require >=30 unique documents")
    categories = Counter(sample["category"] for sample in samples)
    if categories != Counter({"resume": 15, "question_bank": 15}):
        raise SystemExit(f"FAIL: unexpected category distribution: {categories}")
    formats = Counter((sample["category"], sample["format"]) for sample in samples)
    tags = Counter(tag for sample in samples for tag in sample["complexityTags"])
    if not any(sample["sampleId"] == "Q05" and "two-column" in sample["complexityTags"] for sample in samples):
        raise SystemExit("FAIL: Stage 0 Q05 two-column regression sample is missing")
    if sum(count for tag, count in tags.items() if tag in {"table", "two-column", "multi-column", "multi-page", "cross-page", "scanned"}) < 5:
        raise SystemExit("FAIL: fewer than five complex-layout samples")
    for sample in samples:
        file_path = BASE / sample["file"]
        annotation_path = ANNOTATIONS / f"{sample['sampleId']}.json"
        if not file_path.is_file() or sha(file_path) != sample["sha256"]:
            raise SystemExit(f"FAIL: input missing or hash mismatch: {sample['sampleId']}")
        if not annotation_path.is_file():
            raise SystemExit(f"FAIL: annotation missing: {sample['sampleId']}")
        annotation = json.loads(annotation_path.read_text(encoding="utf-8"))
        if annotation["sha256"] != sample["sha256"] or annotation["groundTruth"] != sample["groundTruth"]:
            raise SystemExit(f"FAIL: annotation does not match manifest: {sample['sampleId']}")
    print(json.dumps({"status": "PASS", "datasetId": dataset["datasetId"], "manifestSha256": sha(MANIFEST),
                      "documents": len(samples), "categories": dict(categories),
                      "formats": {f"{kind}/{fmt}": count for (kind, fmt), count in sorted(formats.items())},
                      "complexLayoutTags": dict(tags)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
