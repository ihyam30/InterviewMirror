#!/usr/bin/env python3
"""Create a randomized blind follow-up review pack from two same-dataset runs."""

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


SUPPORTED_RUN_SCHEMAS = {
    "interviewmirror.spring-ai-run.v1.1.0",
    "interviewmirror.spring-ai-run.v1.2.0",
}
PACK_SCHEMA = "interviewmirror.followup-blind-review.v1.0.0"
RATING_FIELDS = ["reviewerId", "caseId", "candidateLabel", "followUpQuality", "questionRelevance", "comment"]


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def redact_identity(value: Any, identifiers: list[str]) -> Any:
    if isinstance(value, str):
        result = value
        for identifier in sorted(set(filter(None, identifiers)), key=len, reverse=True):
            result = re.sub(rf"(?<!\w){re.escape(identifier)}(?!\w)", "[MODEL]", result, flags=re.IGNORECASE)
        return result
    if isinstance(value, list):
        return [redact_identity(item, identifiers) for item in value]
    if isinstance(value, dict):
        return {key: redact_identity(item, identifiers) for key, item in value.items()}
    return value


def evaluation_map(run: dict[str, Any], cases: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    if run.get("schemaVersion") not in SUPPORTED_RUN_SCHEMAS:
        raise ValueError(f"{run.get('provider')}: unsupported run schema {run.get('schemaVersion')}")
    expected_ids = {case["id"] for case in cases}
    outputs: dict[str, dict[str, Any]] = {}
    for call in run.get("calls", []):
        if call.get("kind") != "EVALUATION":
            continue
        case_id = call.get("caseId")
        value = call.get("structuredOutput")
        if (call.get("structured") and call.get("semanticValid") is True
                and isinstance(value, dict) and case_id in expected_ids):
            if case_id in outputs:
                raise ValueError(f"{run.get('provider')}: duplicate evaluation output for {case_id}")
            if type(value.get("followUpRequired")) is not bool:
                raise ValueError(f"{run.get('provider')} {case_id}: followUpRequired must be boolean")
            if not isinstance(value.get("followUpQuestion"), str):
                raise ValueError(f"{run.get('provider')} {case_id}: followUpQuestion must be a string")
            outputs[case_id] = value
    missing = sorted(expected_ids - outputs.keys())
    if missing:
        raise ValueError(f"{run.get('provider')}: evaluation outputs missing for {', '.join(missing)}")
    return outputs


def create_pack(dataset: dict[str, Any], dataset_hash: str, runs: list[dict[str, Any]], seed: int) -> tuple[dict[str, Any], dict[str, Any]]:
    if len(runs) != 2:
        raise ValueError("Exactly two candidate runs are required")
    for run in runs:
        if run.get("schemaVersion") not in SUPPORTED_RUN_SCHEMAS:
            raise ValueError(f"{run.get('provider')}: unsupported run schema")
        if run.get("datasetVersion") != dataset.get("schemaVersion"):
            raise ValueError(f"{run.get('provider')}: dataset version mismatch")
        if run.get("promptVersion") != dataset.get("promptVersion"):
            raise ValueError(f"{run.get('provider')}: prompt version mismatch")
        if run.get("datasetSha256") != dataset_hash:
            raise ValueError(f"{run.get('provider')}: dataset SHA-256 mismatch")
    if runs[0].get("provider") == runs[1].get("provider"):
        raise ValueError("The two runs must come from different providers")

    cases = dataset.get("cases", [])
    expected_ids = {case["id"] for case in cases}
    if len(cases) < 40 or len(expected_ids) != len(cases):
        raise ValueError("Follow-up review requires at least 40 uniquely identified cases")
    if any(case.get("expectedAction") not in {"ASK", "MOVE_ON"} for case in cases):
        raise ValueError("Every case must define expectedAction as ASK or MOVE_ON")

    maps = {run["provider"]: evaluation_map(run, cases) for run in runs}
    providers = [run["provider"] for run in runs]
    provider_order = list(providers)
    random.Random(seed).shuffle(provider_order)
    labels = {provider_order[0]: "A", provider_order[1]: "B"}
    rng = random.Random(seed ^ 0x73C9)
    shuffled_cases = list(cases)
    rng.shuffle(shuffled_cases)

    packed_cases = []
    for case in shuffled_cases:
        candidates = []
        for run in runs:
            output = maps[run["provider"]][case["id"]]
            asked = bool(output.get("followUpRequired"))
            question = output.get("followUpQuestion") or ""
            if asked and not question.strip():
                raise ValueError(f"{run['provider']} {case['id']}: follow-up flag is true but question is empty")
            candidates.append({
                "candidateLabel": labels[run["provider"]],
                "actualAction": "ASK" if asked else "MOVE_ON",
                "followUpQuestion": redact_identity(question, [run.get("provider", ""), run.get("modelId", "")]),
            })
        rng.shuffle(candidates)
        packed_cases.append({
            "caseId": case["id"],
            "mode": case["mode"],
            "dimension": case["dimension"],
            "context": case["context"],
            "question": case["question"],
            "candidateAnswer": case["answer"],
            "candidates": candidates,
        })

    pack = {
        "schemaVersion": PACK_SCHEMA,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "sourceDatasetVersion": dataset["schemaVersion"],
        "sourceDatasetSha256": dataset_hash,
        "sourcePromptVersion": dataset["promptVersion"],
        "ratingScale": {"minimum": 1, "maximum": 5},
        "reviewInstructions": [
            "Judge whether to ask a follow-up from the supplied answer and context; do not assume every answer needs another question.",
            "Score followUpQuality for the overall decision and usefulness: a good ASK is specific, grounded, and advances a material information gap; a good MOVE_ON avoids needless repetition when the answer is sufficient.",
            "Score questionRelevance only when actualAction is ASK; leave it blank for MOVE_ON. Judge whether the question follows naturally from the answer and is answerable.",
            "Do not reward claims that are unsupported by the supplied answer. Do not infer missing experience or penalize a candidate for information the question did not request.",
            "Do not discuss which model you believe produced a candidate. Candidate labels are randomized and provider identities are stored separately.",
        ],
        "ratingAnchors": {
            "5": "Correct ask/stop decision; when asking, one focused, answerable probe addresses an important gap without unsupported assumptions.",
            "4": "Sound decision and relevant follow-up, with only a minor omission or slight breadth.",
            "3": "Partly useful but generic, mildly redundant, premature, or missing an important nuance.",
            "2": "Weak decision or question; substantially repetitive, assumptive, or misses a clearly important probe.",
            "1": "Wrong or off-topic action/question, unsupported premise, or a serious evidence-boundary violation.",
        },
        "criteria": ["followUpQuality", "questionRelevance"],
        "cases": packed_cases,
    }
    key = {
        "schemaVersion": "interviewmirror.followup-blind-review-key.v1.0.0",
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
                writer.writerow({
                    "reviewerId": "", "caseId": case["caseId"],
                    "candidateLabel": candidate["candidateLabel"],
                    "followUpQuality": "", "questionRelevance": "", "comment": "",
                })


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=Path("data/poc/followup-cases.v1.json"))
    parser.add_argument("--qwen", type=Path, default=Path("data/poc/results/models/qwen.followup-40.json"))
    parser.add_argument("--glm", type=Path, default=Path("data/poc/results/models/glm.followup-40.reasoning-low.json"))
    parser.add_argument("--out-dir", type=Path, default=Path("data/poc/results/followup-review-v1"))
    parser.add_argument("--seed", type=int, required=True, help="Keep private until both reviews are complete.")
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
