#!/usr/bin/env python3
"""Reproducibly estimate 50 sessions/month from explicit token/price assumptions."""
import argparse
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sessions", type=int, default=50)
    parser.add_argument("--input-per-session", type=int, default=400_000)
    parser.add_argument("--output-per-session", type=int, default=40_000)
    parser.add_argument("--qwen-input", type=float, default=0.8,
                        help="CNY per million input tokens; default assumes Beijing, <=128K/request, non-thinking")
    parser.add_argument("--qwen-output", type=float, default=2.0,
                        help="CNY per million output tokens; default assumes Beijing, <=128K/request, non-thinking")
    parser.add_argument("--glm-input", type=float, default=2.0, help="must be checked against the provider account")
    parser.add_argument("--glm-output", type=float, default=8.0, help="must be checked against the provider account")
    parser.add_argument("--stress-multiplier", type=float, default=2.0)
    parser.add_argument("--budget-cny", type=float, default=300)
    parser.add_argument("--out", type=Path, default=Path("data/poc/results/cost-estimate.v1.json"))
    args = parser.parse_args()
    rows = []
    for name, input_price, output_price in (
        ("qwen-plus-2025-12-01", args.qwen_input, args.qwen_output),
        ("glm-5.3-flash-assumption", args.glm_input, args.glm_output),
    ):
        regular = args.sessions * (args.input_per_session * input_price + args.output_per_session * output_price) / 1_000_000
        stress = regular * args.stress_multiplier
        rows.append({"model": name, "inputCnyPerMillion": input_price, "outputCnyPerMillion": output_price,
                     "inputTokens": args.sessions * args.input_per_session,
                     "outputTokens": args.sessions * args.output_per_session,
                     "monthlyCny": round(regular, 4), "stressMonthlyCny": round(stress, 4),
                     "withinBudget": stress <= args.budget_cny,
                     "priceStatus": "provider-verified-required" if name.startswith("glm")
                     else "Beijing reference price; assumes <=128K input/request and non-thinking mode"})
    result = {"schemaVersion": "interviewmirror.cost-estimate.v1.0.0", "sessions": args.sessions,
              "tokensPerSession": {"input": args.input_per_session, "output": args.output_per_session},
              "stressMultiplier": args.stress_multiplier, "budgetCny": args.budget_cny, "candidates": rows}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if all(r["withinBudget"] for r in rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
