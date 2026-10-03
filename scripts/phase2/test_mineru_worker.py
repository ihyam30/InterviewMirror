import os
import unittest
from unittest.mock import patch

import mineru_worker


class WorkerBindAddressTests(unittest.TestCase):
    @patch.dict(os.environ, {"MINERU_WORKER_TOKEN": "test-token-" + "x" * 32}, clear=True)
    @patch("mineru_worker.ThreadingHTTPServer")
    def test_main_defaults_to_loopback(self, server_class):
        mineru_worker.main()

        server_class.assert_called_once_with(("127.0.0.1", 8765), mineru_worker.Handler)
        server_class.return_value.serve_forever.assert_called_once_with(poll_interval=0.5)

    @patch.dict(os.environ, {
        "MINERU_WORKER_TOKEN": "test-token-" + "x" * 32,
        "MINERU_WORKER_BIND": "192.0.2.10",
        "MINERU_WORKER_PORT": "9876",
    }, clear=True)
    @patch("mineru_worker.ThreadingHTTPServer")
    def test_explicit_bind_override_is_preserved(self, server_class):
        mineru_worker.main()

        server_class.assert_called_once_with(("192.0.2.10", 9876), mineru_worker.Handler)


if __name__ == "__main__":
    unittest.main()
