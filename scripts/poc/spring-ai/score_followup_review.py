#!/usr/bin/env python3
"""Validate and summarize two blinded human reviews of follow-up quality."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
from statistics import mean
from typing import Any


QUALITY = "followUpQuality"
RELEVANCE = "questionRelevance"
REQUIRED_COLUMNS = {"reviewerId", "caseId", "candidateLabel", QUALITY, RELEVANCE}


def load_ratings(path: Path, pack: dict[str, Any]) -> tuple[str, dict[tuple[str, str], dict[str, Any]]]:
    rows: dict[tuple[str, str], dict[str, Any]] = {}
    reviewers: set[str] = set()
    pack_action = {
        (case["caseId"], candidate["candidateLabel"]): candidate["actualAction"]
        for case in pack.get("cases", []) for candidate in case.get("candidates", [])
    }
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not REQUIRED_COLUMNS.issubset(reader.fieldnames or []):
            raise ValueError(f"{path}: missing required columns")
        for line, row in enumerate(reader, start=2):
            reviewer = (row.get("reviewerId") or "").strip()
            case_id = (row.get("caseId") or "").strip()
            label = (row.get("candidateLabel") or "").strip()
            key = (case_id, label)
            if not reviewer or label not in {"A", "B"} or key not in pack_action:
                raise ValueError(f"{path}:{line}: invalid reviewer, case, or candidate label")
            if key in rows:
                raise ValueError(f"{path}:{line}: duplicate review row for {key}")
            quality = parse_rating(row.get(QUALITY), path, line, QUALITY, required=True)
            asked = pack_action[key] == "ASK"
            relevance = parse_rating(row.get(RELEVANCE), path, line, RELEVANCE, required=asked)
            if not asked and (row.get(RELEVANCE) or "").strip():
                raise ValueError(f"{path}:{line}: questionRelevance must be blank when candidate action is MOVE_ON")
            reviewers.add(reviewer)
            rows[key] = {QUALITY: quality, RELEVANCE: relevance}
    if len(reviewers) != 1:
        raise ValueError(f"{path}: expected exactly one reviewer ID, found {len(reviewers)}")
    return next(iter(reviewers)), rows


def parse_rating(value: str | None, path: Path, line: int, name: str, required: bool) -> int | None:
    value = (value or "").strip()
    if not value and not required:
        return None
    try:
        number = int(value)
    except ValueError as error:
        raise ValueError(f"{path}:{line}: {name} must be an integer 1-5" if required
                         else f"{path}:{line}: {name} must be blank or an integer 1-5") from error
    if number < 1 or number > 5:
        raise ValueError(f"{path}:{line}: {name} must be an integer 1-5")
    return number


def summarize(pack_path: Path, first_path: Path, second_path: Path,
              unblinding_key_path: Path | None = None, dataset_path: Path | None = None) -> dict[str, Any]:
    pack_bytes = pack_path.read_bytes()
    pack = json.loads(pack_bytes)
    if pack.get("schemaVersion") != "interviewmirror.followup-blind-review.v1.0.0":
        raise ValueError("Unsupported blind review pack schema")
    pack_cases = pack.get("cases", [])
    if len(pack_cases) < 40 or len({case.get("caseId") for case in pack_cases}) != len(pack_cases):
        raise ValueError("Blind pack must contain at least 40 uniquely identified cases")
    if any({candidate.get("candidateLabel") for candidate in case.get("candidates", [])} != {"A", "B"}
           or len(case.get("candidates", [])) != 2 for case in pack_cases):
        raise ValueError("Each blind review case must contain exactly one A and one B candidate")
    first_id, first = load_ratings(first_path, pack)
    second_id, second = load_ratings(second_path, pack)
    if first_id == second_id:
        raise ValueError("Reviewer IDs must be different")
    expected = {(case["caseId"], candidate["candidateLabel"])
                for case in pack.get("cases", []) for candidate in case.get("candidates", [])}
    for reviewer_id, ratings in ((first_id, first), (second_id, second)):
        if set(ratings) != expected:
            missing = sorted(expected - ratings.keys())
            extra = sorted(ratings.keys() - expected)
            raise ValueError(f"{reviewer_id}: review rows mismatch; missing={missing}, extra={extra}")

    reviewer_means: dict[str, dict[str, float]] = {}
    for reviewer_id, ratings in ((first_id, first), (second_id, second)):
        reviewer_means[reviewer_id] = {
            label: round(mean(ratings[(case_id, label)][QUALITY]
                             for case_id, candidate_label in expected if candidate_label == label), 4)
            for label in ("A", "B")
        }

    candidate_scores = {}
    for label in ("A", "B"):
        quality_scores = [ratings[key][QUALITY] for ratings in (first, second)
                          for key in expected if key[1] == label]
        relevance_scores = [ratings[key][RELEVANCE] for ratings in (first, second)
                            for key in expected if key[1] == label and ratings[key][RELEVANCE] is not None]
        candidate_scores[label] = {
            "followUpQualityMean": round(mean(quality_scores), 4),
            "reviewerMeans": {reviewer_id: reviewer_means[reviewer_id][label]
                              for reviewer_id in (first_id, second_id)},
            "questionRelevanceMeanWhenAsked": round(mean(relevance_scores), 4) if relevance_scores else None,
            "questionRelevanceRatingCount": len(relevance_scores),
            "reviewedAnswerCountPerReviewer": sum(1 for key in expected if key[1] == label),
            "askedQuestionCount": sum(1 for case in pack["cases"] for candidate in case["candidates"]
                                      if candidate["candidateLabel"] == label and candidate["actualAction"] == "ASK"),
        }

    compared = len(expected)
    within_one = sum(abs(first[key][QUALITY] - second[key][QUALITY]) <= 1 for key in expected)
    result: dict[str, Any] = {
        "schemaVersion": "interviewmirror.followup-review-scores.v1.0.0",
        "blindPackSha256": hashlib.sha256(pack_bytes).hexdigest(),
        "reviewerIds": sorted((first_id, second_id)),
        "candidateScores": candidate_scores,
        "interRaterWithinOnePointRate": round(within_one / compared, 4) if compared else None,
        "qualityScoreComparisons": compared,
        "gate": {"requiredMean": 4.0, "passedCandidateLabels": [label for label in ("A", "B")
                                                                if candidate_scores[label]["followUpQualityMean"] >= 4.0]},
        "unblinded": False,
    }

    if bool(unblinding_key_path) != bool(dataset_path):
        raise ValueError("Provide both --unblinding-key and --dataset, or neither")
    if unblinding_key_path and dataset_path:
        key = json.loads(unblinding_key_path.read_text(encoding="utf-8"))
        dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
        if dataset.get("schemaVersion") != pack.get("sourceDatasetVersion"):
            raise ValueError("Unblinding dataset version does not match blind pack")
        if hashlib.sha256(dataset_path.read_bytes()).hexdigest() != pack.get("sourceDatasetSha256"):
            raise ValueError("Unblinding dataset SHA-256 does not match blind pack")
        mapping = key.get("mapping", {})
        if set(mapping) != {"A", "B"}:
            raise ValueError("Invalid unblinding key")
        result["unblindedCandidateScores"] = {
            mapping[label]["provider"]: {**candidate_scores[label], "candidateLabel": label}
            for label in ("A", "B")
        }
        result["unblinded"] = True
        gold = {case["id"]: case["expectedAction"] for case in dataset.get("cases", [])}
        prediction_by_label = {
            label: {(case["caseId"]): candidate["actualAction"]
                    for case in pack["cases"] for candidate in case["candidates"]
                    if candidate["candidateLabel"] == label}
            for label in ("A", "B")
        }
        metrics = {}
        for label, predicted in prediction_by_label.items():
            if set(predicted) != set(gold):
                raise ValueError("Blind pack cases do not match the unblinding dataset")
            tp = sum(gold[case_id] == "ASK" and action == "ASK" for case_id, action in predicted.items())
            fp = sum(gold[case_id] == "MOVE_ON" and action == "ASK" for case_id, action in predicted.items())
            fn = sum(gold[case_id] == "ASK" and action == "MOVE_ON" for case_id, action in predicted.items())
            correct = sum(gold[case_id] == action for case_id, action in predicted.items())
            metrics[mapping[label]["provider"]] = {
                "annotatedActionAgreement": round(correct / len(gold), 4),
                "askPrecision": round(tp / (tp + fp), 4) if tp + fp else None,
                "askRecall": round(tp / (tp + fn), 4) if tp + fn else None,
                "note": "Diagnostic comparison to author-preannotated expectedAction; not a human gold score.",
            }
        result["authorAnnotationDiagnostics"] = metrics
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, required=True)
    parser.add_argument("--reviewer-1", type=Path, required=True)
    parser.add_argument("--reviewer-2", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--unblinding-key", type=Path)
    parser.add_argument("--dataset", type=Path)
    args = parser.parse_args()
    result = summarize(args.pack, args.reviewer_1, args.reviewer_2, args.unblinding_key, args.dataset)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
