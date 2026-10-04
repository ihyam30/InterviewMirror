#!/usr/bin/env python3
"""Verify the frozen Stage 3 follow-up set is the Phase 0 40-case corpus bound to the new prompt."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "data/poc/followup-cases.v1.json"
DEFAULT_DATASET = ROOT / "data/poc/phase3-followup-cases.v1.json"
EXPECTED_SCHEMA = "interviewmirror.followup-benchmark.v2.0.0"
EXPECTED_PROMPT = "phase3.followup.v1"


def verify_payload(current: dict[str, object], source: dict[str, object], raw: bytes) -> dict[str, object]:
    if current.get("schemaVersion") != EXPECTED_SCHEMA:
        raise ValueError("Stage 3 benchmark schema version mismatch")
    if current.get("promptVersion") != EXPECTED_PROMPT:
        raise ValueError("Stage 3 follow-up prompt version mismatch")
    if source.get("cases") != current.get("cases"):
        raise ValueError("Stage 3 cases changed from the frozen Phase 0 corpus")
    cases = current.get("cases", [])
    ids = [case.get("id") for case in cases]
    if len(cases) != 40 or len(set(ids)) != 40 or any(not value for value in ids):
        raise ValueError("Expected exactly 40 uniquely identified scenarios")
    actions = Counter(case.get("expectedAction") for case in cases)
    if actions != Counter({"ASK": 20, "MOVE_ON": 20}):
        raise ValueError(f"Expected 20 ASK and 20 MOVE_ON cases, received {dict(actions)}")
    if any(not case.get("question", "").strip() or not case.get("answer", "").strip() for case in cases):
        raise ValueError("Every scenario must have a question and a candidate answer")
    return {"caseCount": len(cases), "actionCounts": dict(actions), "datasetSha256": hashlib.sha256(raw).hexdigest(),
            "schemaVersion": current["schemaVersion"], "promptVersion": current["promptVersion"]}


def verify(dataset_path: Path) -> dict[str, object]:
    raw = dataset_path.read_bytes()
    current = json.loads(raw)
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    return verify_payload(current, source, raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    args = parser.parse_args()
    print(json.dumps(verify(args.dataset.resolve()), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
