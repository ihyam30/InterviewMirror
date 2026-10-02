"""Regression tests for question matching and MinerU result provenance."""
from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

from poc_runtime import RUN_SCHEMA_VERSION, generator_metadata, toolchain_lock, validate_run_provenance
from score_parser_outputs import match_questions, question_lines


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class QuestionMatchingTests(unittest.TestCase):
    def test_one_merged_prediction_cannot_match_three_expected_questions(self) -> None:
        expected = ["Explain SSE reconnect behavior.",
                    "How do you persist partial model output?",
                    "What belongs in a trace identifier?"]
        parsed = "Q1: " + " ".join(expected)
        found = question_lines(parsed)

        matches = match_questions(expected, found)

        self.assertEqual(len(found), 1)
        self.assertEqual(len(matches), 1)
        self.assertLessEqual(len(matches) / len(found), 1.0)

    def test_matches_each_question_once(self) -> None:
        questions = ["Explain SSE reconnect behavior.",
                     "How do you persist partial model output?",
                     "What belongs in a trace identifier?"]
        matches = match_questions(questions, questions)
        self.assertEqual(matches, [(0, 0), (1, 1), (2, 2)])

    def test_ambiguous_substring_uses_maximum_cardinality_assignment(self) -> None:
        matches = match_questions(["Java Spring Boot", "Java"], ["Java Spring Boot", "Java"])
        self.assertEqual(len(matches), 2)
        self.assertEqual(len({expected for expected, _ in matches}), 2)
        self.assertEqual(len({predicted for _, predicted in matches}), 2)

    def test_duplicate_prediction_cannot_satisfy_duplicate_expectations(self) -> None:
        matches = match_questions(["same question", "same question"], ["same question"])
        self.assertEqual(len(matches), 1)


class ProvenanceTests(unittest.TestCase):
    def setUp(self) -> None:
        # Keep test fixtures as uniquely named transient files in this existing,
        # writable directory; this runner cannot create arbitrary temp folders.
        self.samples = Path(__file__).resolve().parent
        self.outputs = self.samples
        self.sample_id = "T_Q01"
        source = b"# Synthetic question bank\nQ1: What is provenance?\n"
        self.sample_sha = sha256(source)
        (self.samples / f"{self.sample_id}.txt").write_bytes(source)
        lock = toolchain_lock()
        sample = {
            "schemaVersion": "interviewmirror-samples.v1.1.0", "sampleId": self.sample_id,
            "file": f"{self.sample_id}.txt", "format": "txt", "mediaType": "text/plain",
            "category": "question_bank", "complexityTags": ["text"], "scanned": False,
            "groundTruth": {"questions": ["What is provenance?"]}, "sha256": self.sample_sha,
        }
        (self.samples / f"{self.sample_id}.manifest.json").write_text(json.dumps(sample), encoding="utf-8")
        self.dataset = {
            "schemaVersion": "interviewmirror-samples.v1.1.0",
            "datasetId": "synthetic-test", "sampleCount": 1,
            "generator": generator_metadata(lock),
            "samples": [{"sampleId": self.sample_id, "file": f"{self.sample_id}.txt", "sha256": self.sample_sha}],
        }
        self._write_dataset()
        output = b"Q1: What is provenance?\n"
        self.output_sha = sha256(output)
        (self.outputs / f"{self.sample_id}.md").write_bytes(output)
        self.run = self._run_record()
        self._write_run()

    def tearDown(self) -> None:
        for filename in ("manifest.json", "run-manifest.json", f"{self.sample_id}.txt",
                         f"{self.sample_id}.manifest.json", f"{self.sample_id}.md"):
            (self.samples / filename).unlink(missing_ok=True)

    def _run_record(self) -> dict:
        return {
            "schemaVersion": RUN_SCHEMA_VERSION, "status": "PASS", "datasetId": "synthetic-test",
            "datasetManifestSha256": sha256((self.samples / "manifest.json").read_bytes()),
            "datasetSchemaVersion": "interviewmirror-samples.v1.1.0",
            "generator": self.dataset["generator"],
            "results": [{"sampleId": self.sample_id, "file": f"{self.sample_id}.txt", "sha256": self.sample_sha,
                         "format": "txt", "category": "question_bank", "status": "PASS_DIRECT_TEXT",
                         "output": f"{self.sample_id}.md", "outputSha256": self.output_sha}],
        }

    def _write_dataset(self) -> None:
        (self.samples / "manifest.json").write_text(
            json.dumps(self.dataset, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def _write_run(self) -> None:
        (self.outputs / "run-manifest.json").write_text(
            json.dumps(self.run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def test_valid_run_is_bound_to_dataset_and_output(self) -> None:
        dataset, run, samples = validate_run_provenance(self.samples, self.outputs)
        self.assertEqual(dataset["datasetId"], run["datasetId"])
        self.assertEqual(samples[self.sample_id]["_sourceSha256"], self.sample_sha)

    def test_stale_global_dataset_manifest_is_rejected(self) -> None:
        path = self.samples / "manifest.json"
        path.write_bytes(path.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "dataset manifest SHA-256"):
            validate_run_provenance(self.samples, self.outputs)

    def test_modified_input_is_rejected(self) -> None:
        (self.samples / f"{self.sample_id}.txt").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "input SHA-256"):
            validate_run_provenance(self.samples, self.outputs)

    def test_modified_per_sample_manifest_is_rejected(self) -> None:
        sample_path = self.samples / f"{self.sample_id}.manifest.json"
        sample = json.loads(sample_path.read_text(encoding="utf-8"))
        sample["sha256"] = "0" * 64
        sample_path.write_text(json.dumps(sample), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "input SHA-256"):
            validate_run_provenance(self.samples, self.outputs)

    def test_modified_parse_output_is_rejected(self) -> None:
        (self.outputs / f"{self.sample_id}.md").write_text("tampered", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "output SHA-256"):
            validate_run_provenance(self.samples, self.outputs)

    def test_old_run_manifest_without_output_hash_is_rejected(self) -> None:
        self.run["schemaVersion"] = "interviewmirror.mineru-run.v1.0.0"
        self._write_run()
        with self.assertRaisesRegex(ValueError, "stale run-manifest schema"):
            validate_run_provenance(self.samples, self.outputs)


if __name__ == "__main__":
    unittest.main()
