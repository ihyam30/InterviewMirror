#!/usr/bin/env python3
"""Summarize two independent blind-review CSVs without unblinding model names."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from collections import defaultdict
from pathlib import Path
from statistics import mean
from typing import Any


CRITERIA = ("evidenceAccuracy", "coverage", "actionability")


def load_ratings(path: Path) -> tuple[str, dict[tuple[str, str], dict[str, int]]]:
    rows: dict[tuple[str, str], dict[str, int]] = {}
    reviewer_ids: set[str] = set()
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"reviewerId", "caseId", "candidateLabel", *CRITERIA}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError(f"{path}: missing required columns")
        for row_number, row in enumerate(reader, start=2):
            reviewer_id = (row.get("reviewerId") or "").strip()
            case_id = (row.get("caseId") or "").strip()
            label = (row.get("candidateLabel") or "").strip()
            if not reviewer_id or not case_id or label not in {"A", "B"}:
                raise ValueError(f"{path}:{row_number}: reviewerId, caseId, or candidateLabel is invalid")
            reviewer_ids.add(reviewer_id)
            key = (case_id, label)
            if key in rows:
                raise ValueError(f"{path}:{row_number}: duplicate case/candidate {key}")
            scores: dict[str, int] = {}
            for criterion in CRITERIA:
                try:
                    score = int(row[criterion])
                except (TypeError, ValueError) as error:
                    raise ValueError(f"{path}:{row_number}: {criterion} must be an integer 1-5") from error
                if score < 1 or score > 5:
                    raise ValueError(f"{path}:{row_number}: {criterion} must be an integer 1-5")
                scores[criterion] = score
            rows[key] = scores
    if len(reviewer_ids) != 1:
        raise ValueError(f"{path}: expected exactly one reviewer ID, found {len(reviewer_ids)}")
    return next(iter(reviewer_ids)), rows


def summarize(pack_path: Path, first_path: Path, second_path: Path) -> dict[str, Any]:
    pack = json.loads(pack_path.read_text(encoding="utf-8"))
    first_id, first = load_ratings(first_path)
    second_id, second = load_ratings(second_path)
    if first_id == second_id:
        raise ValueError("Reviewer IDs must be different")
    expected = {(case["caseId"], candidate["candidateLabel"])
                for case in pack.get("cases", []) for candidate in case.get("candidates", [])}
    for reviewer_id, rows in ((first_id, first), (second_id, second)):
        if set(rows) != expected:
            missing = sorted(expected - rows.keys())
            extra = sorted(rows.keys() - expected)
            raise ValueError(f"{reviewer_id}: review rows mismatch; missing={missing}, extra={extra}")

    per_label: dict[str, dict[str, Any]] = {}
    for label in ("A", "B"):
        keys = sorted(key for key in expected if key[1] == label)
        criterion_means = {
            criterion: round(mean([first[key][criterion] for key in keys] +
                                  [second[key][criterion] for key in keys]), 4)
            for criterion in CRITERIA
        }
        per_label[label] = {
            "criterionMeans": criterion_means,
            "overallMean": round(mean(criterion_means.values()), 4),
            "reportCountPerReviewer": len(keys),
        }

    comparisons = len(expected) * len(CRITERIA)
    within_one = 0
    for key in expected:
        for criterion in CRITERIA:
            if abs(first[key][criterion] - second[key][criterion]) <= 1:
                within_one += 1
    return {
        "schemaVersion": "interviewmirror.blind-review-scores.v1.0.0",
        "blindPackSha256": hashlib.sha256(pack_path.read_bytes()).hexdigest(),
        "reviewerIds": sorted([first_id, second_id]),
        "candidateScores": per_label,
        "interRaterWithinOnePointRate": round(within_one / comparisons, 4) if comparisons else None,
        "criterionComparisons": comparisons,
        "unblinded": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, required=True)
    parser.add_argument("--reviewer-1", type=Path, required=True)
    parser.add_argument("--reviewer-2", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = summarize(args.pack, args.reviewer_1, args.reviewer_2)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
