import csv
import hashlib
import json
import unittest
import uuid
from pathlib import Path

import prepare_followup_review
import score_followup_review


class FollowupReviewTests(unittest.TestCase):
    def setUp(self):
        self.dataset = {
            "schemaVersion": "interviewmirror.followup-benchmark.v1.0.0",
            "promptVersion": "interview-followup.v1.0.0",
            "cases": [
                {"id": f"FU{i:02}", "mode": "COMPREHENSIVE", "dimension": "TECHNICAL_DEPTH",
                 "context": "JD requires evaluation discipline.", "question": "How did you validate it?",
                 "answer": "We measured Recall@5.", "expectedAction": "ASK" if i % 2 else "MOVE_ON",
                 "probeObjective": "Ask for measurement details.", "expectedSignals": ["Missing protocol"]}
                for i in range(1, 41)
            ],
        }
        self.dataset_bytes = json.dumps(self.dataset, ensure_ascii=False).encode()
        self.dataset_hash = hashlib.sha256(self.dataset_bytes).hexdigest()
        self.runs = [self.make_run("qwen", "qwen-model"), self.make_run("glm", "glm-model")]
        parent = Path(__file__).resolve().parent / ".test-artifacts"
        parent.mkdir(exist_ok=True)
        self.root = parent / f"followup-{uuid.uuid4().hex}"
        self.root.mkdir()
        self.dataset_path = self.root / "dataset.json"
        self.dataset_path.write_bytes(self.dataset_bytes)

    def tearDown(self):
        for child in self.root.iterdir():
            child.unlink()
        self.root.rmdir()

    def make_run(self, provider, model_id):
        calls = []
        for case in self.dataset["cases"]:
            asked = case["expectedAction"] == "ASK"
            calls.append({
                "kind": "EVALUATION", "caseId": case["id"], "structured": True, "semanticValid": True,
                "structuredOutput": {"followUpRequired": asked,
                                      "followUpQuestion": "What was the fixed test set?" if asked else ""},
            })
        return {"schemaVersion": "interviewmirror.spring-ai-run.v1.2.0", "provider": provider,
                "modelId": model_id, "datasetVersion": self.dataset["schemaVersion"],
                "promptVersion": self.dataset["promptVersion"], "datasetSha256": self.dataset_hash,
                "calls": calls}

    def make_pack_files(self):
        pack, key = prepare_followup_review.create_pack(self.dataset, self.dataset_hash, self.runs, 381)
        pack_path = self.root / "pack.json"
        pack_path.write_text(json.dumps(pack), encoding="utf-8")
        key_path = self.root / "key.json"
        key_path.write_text(json.dumps(key), encoding="utf-8")
        review_paths = [self.root / "review-1.csv", self.root / "review-2.csv"]
        for path, reviewer_id, rating in zip(review_paths, ("human-1", "human-2"), (4, 5)):
            with path.open("w", newline="", encoding="utf-8-sig") as stream:
                writer = csv.DictWriter(stream, fieldnames=prepare_followup_review.RATING_FIELDS)
                writer.writeheader()
                for case in pack["cases"]:
                    for candidate in case["candidates"]:
                        writer.writerow({
                            "reviewerId": reviewer_id, "caseId": case["caseId"],
                            "candidateLabel": candidate["candidateLabel"],
                            "followUpQuality": rating,
                            "questionRelevance": rating if candidate["actualAction"] == "ASK" else "",
                            "comment": "Grounded and useful.",
                        })
        return pack, pack_path, key_path, review_paths

    def test_pack_blinds_expected_action_and_model_identity(self):
        pack, key = prepare_followup_review.create_pack(self.dataset, self.dataset_hash, self.runs, 381)
        rendered = json.dumps(pack, ensure_ascii=False).lower()
        self.assertEqual(len(pack["cases"]), 40)
        self.assertNotIn("probeobjective", rendered)
        self.assertNotIn('"expectedaction"', rendered)
        self.assertNotIn("qwen-model", rendered)
        self.assertNotIn("glm-model", rendered)
        self.assertEqual(set(key["mapping"]), {"A", "B"})

    def test_pack_rejects_hash_mismatch_duplicate_or_missing_outputs(self):
        altered = [dict(run) for run in self.runs]
        altered[0] = {**altered[0], "datasetSha256": "0" * 64}
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            prepare_followup_review.create_pack(self.dataset, self.dataset_hash, altered, 381)
        altered = [dict(run) for run in self.runs]
        altered[0] = {**altered[0], "calls": altered[0]["calls"][:-1]}
        with self.assertRaisesRegex(ValueError, "outputs missing"):
            prepare_followup_review.create_pack(self.dataset, self.dataset_hash, altered, 381)

    def test_score_requires_two_complete_reviews_and_unblinding_is_optional(self):
        pack, pack_path, key_path, reviews = self.make_pack_files()
        blinded = score_followup_review.summarize(pack_path, reviews[0], reviews[1])
        self.assertFalse(blinded["unblinded"])
        self.assertEqual(blinded["candidateScores"]["A"]["followUpQualityMean"], 4.5)
        self.assertEqual(blinded["interRaterWithinOnePointRate"], 1.0)
        self.assertEqual(blinded["qualityScoreComparisons"], 80)
        unblinded = score_followup_review.summarize(pack_path, reviews[0], reviews[1], key_path, self.dataset_path)
        self.assertTrue(unblinded["unblinded"])
        self.assertEqual(set(unblinded["unblindedCandidateScores"]), {"qwen", "glm"})
        self.assertTrue(all(m["annotatedActionAgreement"] == 1.0
                            for m in unblinded["authorAnnotationDiagnostics"].values()))

    def test_question_relevance_must_be_blank_for_move_on(self):
        _, pack_path, _, reviews = self.make_pack_files()
        with reviews[0].open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
            fields = list(rows[0])
        move_on = next(row for row in rows if row["questionRelevance"] == "")
        move_on["questionRelevance"] = "4"
        with reviews[0].open("w", encoding="utf-8-sig", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        with self.assertRaisesRegex(ValueError, "must be blank"):
            score_followup_review.summarize(pack_path, reviews[0], reviews[1])

    def test_dataset_hash_must_match_on_unblind(self):
        _, pack_path, key_path, reviews = self.make_pack_files()
        self.dataset_path.write_bytes(self.dataset_bytes + b" ")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            score_followup_review.summarize(pack_path, reviews[0], reviews[1], key_path, self.dataset_path)


if __name__ == "__main__":
    unittest.main()
