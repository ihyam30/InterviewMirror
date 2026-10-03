#!/usr/bin/env python3
"""Write a reviewable synthetic evaluation snapshot without random task IDs."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "data" / "poc" / "results" / "phase2" / "live-evaluation.json"
DESTINATION = ROOT / "docs" / "phase2" / "results" / "live-evaluation.json"


def main() -> int:
    if not SOURCE.is_file():
        raise SystemExit(f"Missing live result: {SOURCE}")
    result = json.loads(SOURCE.read_text(encoding="utf-8"))
    if result.get("schemaVersion") != "interviewmirror.phase2-live-evaluation.v1.1":
        raise SystemExit("Unsupported live result schema")
    if not all(result.get("gates", {}).values()):
        raise SystemExit("Refusing to publish a snapshot when an acceptance gate failed")
    if len(result.get("samples", [])) < 30:
        raise SystemExit("Refusing to publish a snapshot with fewer than 30 samples")
    snapshot = {**result, "samples": [
        {key: value for key, value in sample.items() if key != "parseTaskId"}
        for sample in result["samples"]
    ]}
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    DESTINATION.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"snapshot={DESTINATION.relative_to(ROOT)} samples={len(snapshot['samples'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
