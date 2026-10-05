import copy
import json
import unittest
from pathlib import Path

from verify_report_eval_dataset import validate_dataset


class ReportEvaluationDatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        dataset = Path(__file__).resolve().parents[2] / "data/phase4/report-eval.v1.json"
        cls.dataset = json.loads(dataset.read_text(encoding="utf-8"))

    def test_dataset_has_required_phase4_coverage(self):
        summary = validate_dataset(self.dataset)
        self.assertGreaterEqual(summary["cases"], 10)
        self.assertGreater(summary["gapEligible"], 0)
        self.assertGreater(summary["specialized"], 0)
        self.assertGreater(summary["comprehensiveWithoutJd"], 0)

    def test_rejects_non_synthetic_input(self):
        dataset = copy.deepcopy(self.dataset)
        dataset["syntheticOnly"] = False
        with self.assertRaisesRegex(ValueError, "must be synthetic"):
            validate_dataset(dataset)

    def test_rejects_wrong_gap_eligibility_annotation(self):
        dataset = copy.deepcopy(self.dataset)
        dataset["cases"][0]["expected"]["gapApplicable"] = False
        with self.assertRaisesRegex(ValueError, "eligibility annotation"):
            validate_dataset(dataset)

    def test_rejects_duplicate_case_id(self):
        dataset = copy.deepcopy(self.dataset)
        dataset["cases"][1]["id"] = dataset["cases"][0]["id"]
        with self.assertRaisesRegex(ValueError, "unique"):
            validate_dataset(dataset)


if __name__ == "__main__":
    unittest.main()
