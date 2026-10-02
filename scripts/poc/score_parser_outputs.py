#!/usr/bin/env python3
"""Compute strict labeled-field F1, question recall and critical-content recall."""
from __future__ import annotations
import argparse
import json
import platform
import re
import subprocess
import sys
from pathlib import Path
from poc_runtime import check_runtime, validate_run_provenance

ROOT = Path(__file__).resolve().parents[2]
SAMPLES = ROOT / "data" / "poc" / "samples"
RUN = ROOT / "data" / "poc" / "results" / "mineru"
FIELD_LABELS = {"name": "Name", "education": "Education", "skills": "Skills", "project": "Project", "metric": "Metric"}


def git_metadata() -> tuple[str, bool | None]:
    try:
        head = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"], capture_output=True,
                              text=True, check=True).stdout.strip()
        dirty = bool(subprocess.run(["git", "-C", str(ROOT), "status", "--porcelain"], capture_output=True,
                                    text=True, check=True).stdout.strip())
        return head, dirty
    except (OSError, subprocess.CalledProcessError):
        return "unknown", None


def norm(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", value.casefold())


def match_questions(expected: list[str], predicted: list[str]) -> list[tuple[int, int]]:
    """Maximum-cardinality one-to-one matching using the scorer's tolerant text rule."""
    expected_norm = [norm(item) for item in expected]
    predicted_norm = [norm(item) for item in predicted]
    candidates = []
    for exp in expected_norm:
        candidates.append([
            index for index, pred in enumerate(predicted_norm)
            if exp and pred and (exp == pred or exp in pred)
        ])

    # Kuhn augmenting paths ensure ambiguous short/long matches do not reduce the
    # maximum possible true-positive count. Each prediction can satisfy one target.
    prediction_to_expected = [-1] * len(predicted)

    def assign(expected_index: int, visited: set[int]) -> bool:
        for prediction_index in candidates[expected_index]:
            if prediction_index in visited:
                continue
            visited.add(prediction_index)
            previous = prediction_to_expected[prediction_index]
            if previous == -1 or assign(previous, visited):
                prediction_to_expected[prediction_index] = expected_index
                return True
        return False

    # Constrained expectations first makes the assignment stable and efficient;
    # augmenting paths still guarantee maximum cardinality.
    order = sorted(range(len(expected)), key=lambda index: (len(candidates[index]), index))
    for expected_index in order:
        assign(expected_index, set())
    return sorted(
        ((expected_index, prediction_index)
         for prediction_index, expected_index in enumerate(prediction_to_expected)
         if expected_index != -1),
        key=lambda pair: pair[0],
    )


def extract_fields(text: str) -> dict[str, str]:
    found = {}
    for line in text.splitlines():
        cells = [c.strip().strip("`*_") for c in line.strip().strip("|").split("|")]
        if len(cells) >= 2:
            label, value = cells[0].rstrip(":").casefold(), cells[1]
            for key, printable in FIELD_LABELS.items():
                if label == printable.casefold() and value:
                    found[key] = value
        match = re.match(r"^\s*\**(Name|Education|Skills|Project|Metric)\**\s*[:|]\s*(.*?)\s*\**$", line, re.I)
        if match:
            label = match.group(1).casefold()
            found[next(k for k, v in FIELD_LABELS.items() if v.casefold() == label)] = match.group(2).strip(" |`*_\t")
    return found


def question_lines(text: str) -> list[str]:
    found = []
    for line in text.splitlines():
        clean = line.strip().strip("`*_ ")
        if not clean:
            continue
        cells = [c.strip() for c in clean.strip("|").split("|")]
        if len(cells) >= 2 and re.match(r"^(?:Q|Question)\s*\d+\b", cells[0], re.I):
            found.append(cells[1])
        elif re.match(r"^(?:#{1,6}\s*)?(?:Q|Question)\s*\d+\s*[:.)、|\-]?\s*\S", clean, re.I):
            value = re.sub(r"^(?:#{1,6}\s*)?(?:Q|Question)\s*\d+\s*[:.)、|\-]?\s*", "", clean, flags=re.I)
            found.append(value)
    return found


def main() -> int:
    check_runtime()
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=RUN)
    parser.add_argument("--out", type=Path, default=ROOT / "data" / "poc" / "results" / "mineru-metrics.json")
    args = parser.parse_args()
    try:
        dataset, run_manifest, sample_manifests = validate_run_provenance(SAMPLES, args.output_dir)
    except ValueError as exc:
        print(f"Provenance validation failed: {exc}", file=sys.stderr)
        return 2
    git_head, git_dirty = git_metadata()
    resume = {"tp": 0, "fp": 0, "fn": 0, "samples": 0}
    bank = {"tp": 0, "predicted": 0, "expected": 0, "samples": 0}
    critical = {"tp": 0, "expected": 0}
    per_sample = []
    for item in dataset["samples"]:
        sample = sample_manifests[item["sampleId"]]
        output = args.output_dir / f"{item['sampleId']}.md"
        text = output.read_text(encoding="utf-8", errors="replace")
        gt = sample["groundTruth"]
        if sample["category"] == "resume":
            actual = gt["fields"]
            predicted = extract_fields(text)
            tp = sum(key in predicted and norm(predicted[key]) == norm(value) for key, value in actual.items())
            fp = sum(key not in actual or norm(actual[key]) != norm(value) for key, value in predicted.items())
            fn = len(actual) - tp
            resume["tp"] += tp; resume["fp"] += fp; resume["fn"] += fn; resume["samples"] += 1
            for key, value in actual.items():
                critical["expected"] += 1
                critical["tp"] += bool(key in predicted and norm(predicted[key]) == norm(value))
            per_sample.append({"sampleId": item["sampleId"], "kind": "resume", "tp": tp, "fp": fp, "fn": fn,
                               "precision": tp / (tp + fp) if tp + fp else 0,
                               "recall": tp / (tp + fn) if tp + fn else 0})
        elif sample["category"] == "question_bank":
            expected = gt["questions"]
            found = question_lines(text)
            matches = match_questions(expected, found)
            tp = len(matches)
            matched_expected = {expected_index for expected_index, _ in matches}
            bank["tp"] += tp; bank["predicted"] += len(found); bank["expected"] += len(expected); bank["samples"] += 1
            for expected_index, _q in enumerate(expected):
                critical["expected"] += 1
                critical["tp"] += expected_index in matched_expected
            per_sample.append({"sampleId": item["sampleId"], "kind": "question_bank", "tp": tp,
                               "predicted": len(found), "expected": len(expected),
                               "precision": tp / len(found) if found else 0,
                               "recall": tp / len(expected) if expected else 0,
                               "matches": [{"expectedIndex": i, "predictedIndex": j} for i, j in matches]})
        elif sample["category"] == "jd":
            for req in gt["requirements"]:
                critical["expected"] += 1
                critical["tp"] += norm(req) in norm(text)
    precision = resume["tp"] / (resume["tp"] + resume["fp"]) if resume["tp"] + resume["fp"] else 0
    recall = resume["tp"] / (resume["tp"] + resume["fn"]) if resume["tp"] + resume["fn"] else 0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0
    q_recall = bank["tp"] / bank["expected"] if bank["expected"] else 0
    critical_recall = critical["tp"] / critical["expected"] if critical["expected"] else 0
    result = {
        "schemaVersion": "interviewmirror.mineru-metrics.v1.1.0", "datasetId": dataset["datasetId"],
        "datasetManifestSha256": run_manifest["datasetManifestSha256"],
        "runSchemaVersion": run_manifest["schemaVersion"],
        "runStartedAtEpoch": run_manifest.get("startedAtEpoch"),
        "mineruVersion": run_manifest.get("mineruVersion"),
        "gitHead": git_head, "gitDirty": git_dirty, "platform": platform.platform(), "python": sys.version,
        "resumeField": {**resume, "precision": precision, "recall": recall, "f1": f1},
        "questionBank": {**bank, "precision": bank["tp"] / bank["predicted"] if bank["predicted"] else 0, "recall": q_recall},
        "criticalContent": {**critical, "recall": critical_recall},
        "gates": {"resumeF1AtLeast90": f1 >= .9, "questionRecallAtLeast90": q_recall >= .9,
                  "criticalContentAtLeast95": critical_recall >= .95},
        "perSample": per_sample,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"resumeF1": round(f1, 4), "questionRecall": round(q_recall, 4),
                      "criticalContentRecall": round(critical_recall, 4), "gates": result["gates"],
                      "resultPath": str(args.out)}, ensure_ascii=False, indent=2))
    return 0 if all(result["gates"].values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
