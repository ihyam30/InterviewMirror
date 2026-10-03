import hashlib
import unittest

from verify_eval_dataset import sha_bytes


class DatasetHashTests(unittest.TestCase):
    def test_text_hash_is_independent_of_checkout_newlines(self):
        lf = b"first line\nsecond line\n"
        crlf = b"first line\r\nsecond line\r\n"

        self.assertEqual(sha_bytes(lf, ".md"), sha_bytes(crlf, ".txt"))

    def test_binary_hash_does_not_normalize_line_feed_bytes(self):
        lf = b"%PDF\ncontent"
        crlf = b"%PDF\r\ncontent"

        self.assertNotEqual(sha_bytes(lf, ".pdf"), sha_bytes(crlf, ".pdf"))
        self.assertEqual(sha_bytes(lf, ".pdf"), hashlib.sha256(lf).hexdigest())


if __name__ == "__main__":
    unittest.main()
