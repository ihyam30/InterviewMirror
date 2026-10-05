#!/usr/bin/env python3
"""Validate the synthetic fixtures used by the opt-in Phase 4 LLM evaluation."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATASET = ROOT / "data" / "phase4" / "report-eval.v1.json"


def validate_dataset(dataset: dict) -> dict:
    if dataset.get("schemaVersion") != "interviewmirror.phase4-report-evaluation.v1":
        raise ValueError("unsupported report evaluation schema")
    if dataset.get("syntheticOnly") is not True:
        raise ValueError("report evaluation data must be synthetic")
    cases = dataset.get("cases")
    if not isinstance(cases, list) or len(cases) < 10:
        raise ValueError("at least 10 synthetic report cases are required")

    ids = set()
    eligible = specialized = no_jd = early = sparse = 0
    for case in cases:
        case_id = case.get("id")
        if not isinstance(case_id, str) or not case_id or case_id in ids:
            raise ValueError("case ids must be non-empty and unique")
        ids.add(case_id)
        if case.get("mode") not in {"COMPREHENSIVE", "QUESTION_BANK"}:
            raise ValueError(f"{case_id}: invalid mode")
        if case.get("completionReason") not in {"TARGET_REACHED", "USER_ENDED"}:
            raise ValueError(f"{case_id}: invalid completion reason")
        if not isinstance(case.get("resume"), dict) or not isinstance(case.get("jdText"), str):
            raise ValueError(f"{case_id}: resume and jdText are required")
        turns = case.get("turns")
        if not isinstance(turns, list) or not turns:
            raise ValueError(f"{case_id}: at least one answered turn is required")
        for turn in turns:
            if not isinstance(turn.get("question"), str) or not turn["question"].strip():
                raise ValueError(f"{case_id}: question cannot be blank")
            if not isinstance(turn.get("answer"), str) or not turn["answer"].strip():
                raise ValueError(f"{case_id}: answer cannot be blank")

        actual_eligible = case["mode"] == "COMPREHENSIVE" and bool(case["jdText"].strip())
        expected = case.get("expected", {})
        if expected.get("gapApplicable") is not actual_eligible:
            raise ValueError(f"{case_id}: gap eligibility annotation disagrees with mode/JD")
        if expected.get("earlyEnded", False) != (case["completionReason"] == "USER_ENDED"):
            raise ValueError(f"{case_id}: early-end annotation disagrees with completion reason")
        eligible += int(actual_eligible)
        specialized += int(case["mode"] == "QUESTION_BANK")
        no_jd += int(case["mode"] == "COMPREHENSIVE" and not case["jdText"].strip())
        early += int(case["completionReason"] == "USER_ENDED")
        sparse += int(bool(expected.get("sparseEvidence")))

    if not all((eligible, specialized, no_jd, early, sparse)):
        raise ValueError("fixtures must cover eligible gap, specialized, no-JD, early-end and sparse-evidence cases")
    schemas = ROOT / "docs" / "phase4" / "schemas"
    for name in ("report.v1.2.schema.json", "gap-analysis.v1.2.schema.json"):
        schema = json.loads((schemas / name).read_text(encoding="utf-8"))
        if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            raise ValueError(f"{name}: expected JSON Schema Draft 2020-12")
    return {"cases": len(cases), "gapEligible": eligible, "specialized": specialized,
            "comprehensiveWithoutJd": no_jd, "earlyEnded": early, "sparseEvidence": sparse,
            "reportSchemas": 2}


def main() -> None:
    try:
        dataset = json.loads(DATASET.read_text(encoding="utf-8"))
        summary = validate_dataset(dataset)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        raise SystemExit(f"FAIL: {exc}") from exc
    print("PASS: phase4 report evaluation dataset", json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
