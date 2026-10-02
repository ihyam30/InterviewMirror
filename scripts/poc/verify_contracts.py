#!/usr/bin/env python3
"""Sanity-check versioned JSON contracts and mode-specific required fields."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "docs/phase0/schemas"


def main() -> int:
    errors = []
    schemas = {}
    for path in sorted(SCHEMAS.glob("*.json")):
        try:
            schema = json.loads(path.read_text(encoding="utf-8"))
            if not schema.get("$id") or not schema.get("$schema"):
                errors.append(f"{path.name}: missing schema id or dialect")
            schemas[path.name] = schema
        except Exception as exc:
            errors.append(f"{path.name}: {exc}")
    req = schemas.get("interview-request.v1.schema.json", {})
    props = req.get("properties", {})
    enum = props.get("mode", {}).get("enum", [])
    if not {"COMPREHENSIVE", "QUESTION_BANK"}.issubset(enum):
        errors.append("request schema: missing both mode enum values")
    branches = {b.get("properties", {}).get("mode", {}).get("const"): b for b in req.get("oneOf", [])}
    comprehensive = branches.get("COMPREHENSIVE", {})
    if "resumeId" not in comprehensive.get("required", []):
        errors.append("request schema: COMPREHENSIVE does not require resumeId")
    if "questionBankId" not in comprehensive.get("not", {}).get("required", []):
        errors.append("request schema: COMPREHENSIVE does not forbid questionBankId")
    question_bank = branches.get("QUESTION_BANK", {})
    if "questionBankId" not in question_bank.get("required", []):
        errors.append("request schema: QUESTION_BANK does not require questionBankId")
    forbidden_any = question_bank.get("not", {}).get("anyOf", [])
    forbidden_fields = {field for condition in forbidden_any for field in condition.get("required", [])}
    if not {"resumeId", "jdText"}.issubset(forbidden_fields):
        errors.append("request schema: QUESTION_BANK must forbid resumeId and jdText")
    report = schemas.get("report.v1.schema.json", {})
    score_dimensions = report.get("properties", {}).get("scores", {}).get("required", [])
    if len(score_dimensions) != 7:
        errors.append(f"report schema: expected 7 score dimensions, found {len(score_dimensions)}")
    model_run = schemas.get("model-run.v1.2.schema.json", {})
    model_run_props = model_run.get("properties", {})
    if model_run_props.get("schemaVersion", {}).get("const") != "interviewmirror.spring-ai-run.v1.2.0":
        errors.append("model run schema: v1.2 schemaVersion mismatch")
    if not {"requestTimeoutSeconds", "reasoningEffort"}.issubset(model_run.get("required", [])):
        errors.append("model run schema: v1.2 must record timeout and reasoning effort")
    call_props = model_run.get("$defs", {}).get("callResult", {}).get("properties", {})
    if not {"providerNativeUsageType", "providerNativeUsage"}.issubset(call_props):
        errors.append("model run schema: v1.2 must allow provider-native usage details")
    for mode, request in (
        ("COMPREHENSIVE", {"schemaVersion": "1.0.0", "clientRequestId": "contract-test", "mode": "COMPREHENSIVE", "resumeId": "resume-1"}),
        ("QUESTION_BANK", {"schemaVersion": "1.0.0", "clientRequestId": "contract-test", "mode": "QUESTION_BANK", "questionBankId": "bank-1"}),
    ):
        branch = branches.get(mode, {})
        if not set(branch.get("required", [])).issubset(request.keys()):
            errors.append(f"request schema: valid {mode} fixture misses branch required fields")
        if request.get("mode") != branch.get("properties", {}).get("mode", {}).get("const"):
            errors.append(f"request schema: valid {mode} fixture mismatches its discriminator")
    if len(schemas) != 5:
        errors.append(f"expected 5 versioned schemas, found {len(schemas)}")
    print(json.dumps({"status": "PASS" if not errors else "FAIL", "schemas": sorted(schemas), "errors": errors}, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    import json
    raise SystemExit(main())
