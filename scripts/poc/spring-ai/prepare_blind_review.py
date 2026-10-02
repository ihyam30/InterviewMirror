#!/usr/bin/env python3
"""Create a de-identified paired report pack from two v1.1/v1.2 benchmark runs."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


RUN_SCHEMA = "interviewmirror.spring-ai-run.v1.1.0"
RUN_SCHEMA_V1_2 = "interviewmirror.spring-ai-run.v1.2.0"
SUPPORTED_RUN_SCHEMAS = {RUN_SCHEMA, RUN_SCHEMA_V1_2}
PACK_SCHEMA = "interviewmirror.blind-review.v1.0.0"
RATING_FIELDS = ["reviewerId", "caseId", "candidateLabel", "evidenceAccuracy", "coverage", "actionability", "comment"]


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def report_map(run: dict[str, Any], cases: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    if run.get("schemaVersion") not in SUPPORTED_RUN_SCHEMAS:
        supported = ", ".join(sorted(SUPPORTED_RUN_SCHEMAS))
        raise ValueError(f"{run.get('provider', 'run')}: requires one of {supported}")
    expected_ids = {case["id"] for case in cases}
    reports: dict[str, dict[str, Any]] = {}
    for call in run.get("calls", []):
        if call.get("kind") != "REPORT":
            continue
        case_id = call.get("caseId")
        output = call.get("structuredOutput")
        if call.get("structured") and isinstance(output, dict) and case_id in expected_ids:
            reports[case_id] = output
    missing = sorted(expected_ids - reports.keys())
    if missing:
        raise ValueError(f"{run.get('provider')}: report outputs missing for {', '.join(missing)}")
    return reports


def redact_model_identity(value: Any, identifiers: list[str]) -> Any:
    if isinstance(value, str):
        redacted = value
        for identifier in sorted(set(filter(None, identifiers)), key=len, reverse=True):
            redacted = re.sub(rf"(?<!\w){re.escape(identifier)}(?!\w)", "[MODEL]", redacted, flags=re.IGNORECASE)
        return redacted
    if isinstance(value, list):
        return [redact_model_identity(item, identifiers) for item in value]
    if isinstance(value, dict):
        return {key: redact_model_identity(item, identifiers) for key, item in value.items()}
    return value


def create_pack(dataset: dict[str, Any], dataset_hash: str, runs: list[dict[str, Any]], seed: int) -> tuple[dict[str, Any], dict[str, Any]]:
    if len(runs) != 2:
        raise ValueError("Exactly two candidate runs are required")
    for run in runs:
        if run.get("datasetVersion") != dataset.get("schemaVersion"):
            raise ValueError(f"{run.get('provider')}: dataset version mismatch")
        if run.get("promptVersion") != dataset.get("promptVersion"):
            raise ValueError(f"{run.get('provider')}: prompt version mismatch")
        if run.get("datasetSha256") != dataset_hash:
            raise ValueError(f"{run.get('provider')}: dataset SHA-256 mismatch")
    if runs[0].get("provider") == runs[1].get("provider"):
        raise ValueError("The two runs must come from different providers")

    cases = dataset.get("cases", [])
    provider_order = [run["provider"] for run in runs]
    random.Random(seed).shuffle(provider_order)
    labels = {provider_order[0]: "A", provider_order[1]: "B"}
    maps = {run["provider"]: report_map(run, cases) for run in runs}
    rng = random.Random(seed ^ 0x5A17)
    ordered_cases = list(cases)
    rng.shuffle(ordered_cases)

    packed_cases = []
    for case in ordered_cases:
        candidates = [
            {"candidateLabel": labels[run["provider"]],
             "report": redact_model_identity(maps[run["provider"]][case["id"]],
                                             [run["provider"], run["modelId"]])}
            for run in runs
        ]
        rng.shuffle(candidates)
        packed_cases.append({
            "caseId": case["id"],
            "mode": case["mode"],
            "dimension": case["dimension"],
            "question": case["question"],
            "candidateAnswer": case["answer"],
            "candidates": candidates,
        })

    pack = {
        "schemaVersion": PACK_SCHEMA,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "sourceDatasetVersion": dataset["schemaVersion"],
        "sourceDatasetSha256": dataset_hash,
        "ratingScale": {"minimum": 1, "maximum": 5},
        "criteria": ["evidenceAccuracy", "coverage", "actionability"],
        "cases": packed_cases,
    }
    key = {
        "schemaVersion": "interviewmirror.blind-review-key.v1.0.0",
        "seed": seed,
        "mapping": {label: {"provider": run["provider"], "modelId": run["modelId"]}
                    for run in runs for label in [labels[run["provider"]]]},
    }
    return pack, key


def write_template(path: Path, pack: dict[str, Any]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=RATING_FIELDS)
        writer.writeheader()
        for case in pack["cases"]:
            for candidate in case["candidates"]:
                writer.writerow({"reviewerId": "", "caseId": case["caseId"],
                                 "candidateLabel": candidate["candidateLabel"], "evidenceAccuracy": "",
                                 "coverage": "", "actionability": "", "comment": ""})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=Path("data/poc/model-cases.v1.json"))
    parser.add_argument("--qwen", type=Path, default=Path("data/poc/results/models/qwen.json"))
    parser.add_argument("--glm", type=Path, default=Path("data/poc/results/models/glm.json"))
    parser.add_argument("--out-dir", type=Path, default=Path("data/poc/results/review"))
    parser.add_argument("--seed", type=int, required=True, help="Keep this seed private from reviewers; it is saved only in the unblinding key.")
    args = parser.parse_args()

    dataset_bytes = args.dataset.read_bytes()
    dataset_hash = hashlib.sha256(dataset_bytes).hexdigest()
    dataset = json.loads(dataset_bytes)
    runs = [read_json(args.qwen), read_json(args.glm)]
    pack, key = create_pack(dataset, dataset_hash, runs, args.seed)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    pack_path = args.out_dir / "blind-pack.v1.json"
    key_path = args.out_dir / "unblinding-key.v1.json"
    template_path = args.out_dir / "review-template.v1.csv"
    pack_path.write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    key_path.write_text(json.dumps(key, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_template(template_path, pack)
    print(json.dumps({"schemaVersion": PACK_SCHEMA, "caseCount": len(pack["cases"]),
                      "reviewRows": len(pack["cases"]) * 2, "blindPack": str(pack_path),
                      "ratingTemplate": str(template_path), "unblindingKey": str(key_path)},
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
