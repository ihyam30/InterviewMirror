import csv
import json
import unittest
import uuid
from pathlib import Path

import prepare_blind_review
import score_blind_review


class BlindReviewTests(unittest.TestCase):
    def setUp(self):
        self.dataset = {
            "schemaVersion": "interviewmirror.model-benchmark.v1.0.0",
            "promptVersion": "interview-evaluation.v1.0.0",
            "cases": [
                {"id": "M01", "mode": "COMPREHENSIVE", "dimension": "TECHNICAL_DEPTH",
                 "question": "Why?", "answer": "I measured Recall@5.", "shouldAssess": True},
                {"id": "M02", "mode": "QUESTION_BANK", "dimension": "UNASSESSED",
                 "question": "What?", "answer": "I do not know.", "shouldAssess": True},
            ],
        }
        self.dataset_hash = "a" * 64
        self.runs = [self.make_run("qwen", "qwen-snapshot", "Qwen text"),
                     self.make_run("glm", "glm-snapshot", "GLM text")]

    def make_run(self, provider, model_id, output_text):
        calls = []
        for case in self.dataset["cases"]:
            calls.append({"kind": "REPORT", "caseId": case["id"], "structured": True,
                          "structuredOutput": {"overallReview": output_text, "strengths": [], "risks": [],
                                               "recommendations": [], "learningPath": [], "evidence": []}})
        return {"schemaVersion": prepare_blind_review.RUN_SCHEMA, "provider": provider, "modelId": model_id,
                "datasetVersion": self.dataset["schemaVersion"], "promptVersion": self.dataset["promptVersion"],
                "datasetSha256": self.dataset_hash, "calls": calls}

    def test_pack_hides_provider_identity_but_writes_private_unblinding_key(self):
        pack, key = prepare_blind_review.create_pack(self.dataset, self.dataset_hash, self.runs, 381)
        rendered = json.dumps(pack)
        self.assertEqual(len(pack["cases"]), 2)
        self.assertNotIn("qwen", rendered.lower())
        self.assertNotIn("glm", rendered.lower())
        self.assertEqual(set(key["mapping"]), {"A", "B"})
        self.assertEqual({entry["candidateLabel"] for entry in pack["cases"][0]["candidates"]}, {"A", "B"})

    def test_pack_rejects_runs_with_mismatched_dataset_hash(self):
        self.runs[1]["datasetSha256"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            prepare_blind_review.create_pack(self.dataset, self.dataset_hash, self.runs, 381)

    def test_pack_rejects_missing_structured_report(self):
        self.runs[1]["calls"] = self.runs[1]["calls"][:1]
        with self.assertRaisesRegex(ValueError, "report outputs missing"):
            prepare_blind_review.create_pack(self.dataset, self.dataset_hash, self.runs, 381)

    def test_pack_accepts_v1_2_run_with_usage_metadata(self):
        self.runs[1]["schemaVersion"] = prepare_blind_review.RUN_SCHEMA_V1_2
        self.runs[1]["reasoningEffort"] = "low"
        self.runs[1]["requestTimeoutSeconds"] = 60
        pack, _ = prepare_blind_review.create_pack(self.dataset, self.dataset_hash, self.runs, 381)
        self.assertEqual(len(pack["cases"]), 2)

    def test_review_scoring_requires_two_complete_distinct_reviewers(self):
        pack, _ = prepare_blind_review.create_pack(self.dataset, self.dataset_hash, self.runs, 381)
        parent = Path(__file__).resolve().parent / ".test-artifacts"
        parent.mkdir(exist_ok=True)
        root = parent / f"review-{uuid.uuid4().hex}"
        root.mkdir()
        try:
            pack_path = root / "pack.json"
            pack_path.write_text(json.dumps(pack), encoding="utf-8")
            paths = [root / "r1.csv", root / "r2.csv"]
            for path, reviewer_id, score in zip(paths, ("reviewer-1", "reviewer-2"), (4, 5)):
                with path.open("w", newline="", encoding="utf-8") as stream:
                    writer = csv.DictWriter(stream, fieldnames=prepare_blind_review.RATING_FIELDS)
                    writer.writeheader()
                    for case in pack["cases"]:
                        for candidate in case["candidates"]:
                            writer.writerow({"reviewerId": reviewer_id, "caseId": case["caseId"],
                                             "candidateLabel": candidate["candidateLabel"],
                                             "evidenceAccuracy": score, "coverage": score,
                                             "actionability": score, "comment": ""})
            result = score_blind_review.summarize(pack_path, paths[0], paths[1])
        finally:
            for item in root.iterdir():
                item.unlink()
            root.rmdir()
            try:
                parent.rmdir()
            except OSError:
                pass
        self.assertEqual(result["candidateScores"]["A"]["overallMean"], 4.5)
        self.assertEqual(result["interRaterWithinOnePointRate"], 1.0)
        self.assertFalse(result["unblinded"])


if __name__ == "__main__":
    unittest.main()
