import unittest
import json

import verify_phase3_dataset


class Phase3DatasetTests(unittest.TestCase):
    def test_frozen_dataset_has_40_cases_and_balanced_expected_actions(self):
        result = verify_phase3_dataset.verify(verify_phase3_dataset.DEFAULT_DATASET)
        self.assertEqual(result["caseCount"], 40)
        self.assertEqual(result["actionCounts"], {"ASK": 20, "MOVE_ON": 20})
        self.assertEqual(result["promptVersion"], "phase3.followup.v1")

    def test_changed_cases_are_rejected(self):
        current = json.loads(verify_phase3_dataset.DEFAULT_DATASET.read_text(encoding="utf-8"))
        source = json.loads(verify_phase3_dataset.SOURCE.read_text(encoding="utf-8"))
        current["cases"][0]["answer"] += " changed"
        with self.assertRaises(ValueError):
            verify_phase3_dataset.verify_payload(current, source, b"changed fixture")


if __name__ == "__main__":
    unittest.main()
