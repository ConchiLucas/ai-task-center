#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


SCRIPT = Path(__file__).with_name("task-workflow")
TOKEN = "secret-callback-token"
HASH = "a" * 64


class CallbackHandler(BaseHTTPRequestHandler):
    response_status = 200
    response_body = {"code": 0, "data": {"currentStep": "RESULT_VALIDATION"}, "msg": "ok"}
    response_delay_seconds = 0
    requests = []

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"])).decode("utf-8")
        type(self).requests.append((self.path, json.loads(body)))
        time.sleep(type(self).response_delay_seconds)
        encoded = json.dumps(type(self).response_body).encode("utf-8")
        self.send_response(type(self).response_status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        try:
            self.wfile.write(encoded)
        except BrokenPipeError:
            pass

    def log_message(self, format, *args):
        pass


class TaskWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), CallbackHandler)
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()
        cls.base_url = "http://127.0.0.1:%d" % cls.server.server_port

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.server_thread.join()

    def setUp(self):
        CallbackHandler.response_status = 200
        CallbackHandler.response_body = {"code": 0, "data": {}, "msg": "ok"}
        CallbackHandler.response_delay_seconds = 0
        CallbackHandler.requests = []

    def test_posts_prompt_builder_callback_payload_to_environment_base_url(self):
        result = self.run_workflow("--entity-ids", "31,29,30")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([("/api/task/42/onboarding/report", {
            "stage": "batch",
            "token": TOKEN,
            "artifact": "src/main/java/Generator.java",
            "artifactHash": HASH,
            "entityIds": [31, 29, 30],
        })], CallbackHandler.requests)

    def test_non_2xx_response_fails_with_backend_message(self):
        CallbackHandler.response_status = 400
        CallbackHandler.response_body = {"code": 7, "data": None, "msg": "Callback token is invalid"}

        result = self.run_workflow("--entity-ids", "1")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Callback token is invalid", result.stderr)

    def test_backend_failure_envelope_fails_even_with_http_success(self):
        CallbackHandler.response_body = {"code": 7, "data": None, "msg": "callback rejected"}

        result = self.run_workflow("--entity-ids", "1")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("callback rejected", result.stderr)

    def test_rejects_malformed_entity_ids_without_request(self):
        result = self.run_workflow("--entity-ids", "1,nope")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("entity IDs", result.stderr)
        self.assertEqual([], CallbackHandler.requests)

    def test_accepts_batch_run_id_that_matches_a_result_id(self):
        result = self.run_workflow("--entity-ids", "31,31,29")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([31, 31, 29], CallbackHandler.requests[0][1]["entityIds"])

    def test_rejects_duplicate_batch_result_ids_without_request(self):
        result = self.run_workflow("--entity-ids", "31,29,29")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Duplicate entity IDs", result.stderr)
        self.assertEqual([], CallbackHandler.requests)

    def test_rejects_duplicate_result_stage_ids_without_request(self):
        result = self.run_workflow("--entity-ids", "31,31", stage="result")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Duplicate entity IDs", result.stderr)
        self.assertEqual([], CallbackHandler.requests)

    def test_rejects_abbreviated_report_flag_without_request(self):
        result = self.run_workflow("--entity-ids", "1", "--to", "replacement-token")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("unrecognized arguments: --to", result.stderr)
        self.assertEqual([], CallbackHandler.requests)

    def test_redacts_token_from_backend_errors(self):
        CallbackHandler.response_body = {"code": 7, "data": None, "msg": "token %s rejected" % TOKEN}

        result = self.run_workflow("--entity-ids", "1")

        self.assertNotEqual(0, result.returncode)
        self.assertNotIn(TOKEN, result.stderr)
        self.assertIn("[REDACTED]", result.stderr)

    def test_timeout_returns_nonzero_without_exposing_token(self):
        CallbackHandler.response_delay_seconds = 0.2

        result = self.run_workflow(
            "--entity-ids", "1", environment_updates={"TASK_CENTER_HTTP_TIMEOUT_SECONDS": "0.05"})

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Connection error", result.stderr)
        self.assertNotIn(TOKEN, result.stderr)

    def test_rejects_invalid_timeout_without_request(self):
        result = self.run_workflow(
            "--entity-ids", "1", environment_updates={"TASK_CENTER_HTTP_TIMEOUT_SECONDS": "0"})

        self.assertNotEqual(0, result.returncode)
        self.assertIn("TASK_CENTER_HTTP_TIMEOUT_SECONDS", result.stderr)
        self.assertEqual([], CallbackHandler.requests)

    def test_connection_error_returns_nonzero_without_exposing_token(self):
        result = self.run_workflow(
            "--entity-ids", "1", environment_updates={"TASK_CENTER_BASE_URL": "http://127.0.0.1:1"})

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Connection error", result.stderr)
        self.assertNotIn(TOKEN, result.stderr)

    def run_workflow(self, *extra_args, stage="batch", environment_updates=None):
        environment = os.environ.copy()
        environment["TASK_CENTER_BASE_URL"] = self.base_url
        if environment_updates:
            environment.update(environment_updates)
        return subprocess.run(
            [
                sys.executable, str(SCRIPT), "report",
                "--task-config-id", "42",
                "--stage", stage,
                "--token", TOKEN,
                "--artifact", "src/main/java/Generator.java",
                "--artifact-hash", HASH,
                *extra_args,
            ],
            cwd=SCRIPT.parents[1],
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )


if __name__ == "__main__":
    unittest.main()
