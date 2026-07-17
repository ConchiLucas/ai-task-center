import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_text_executors", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(worker)


class FakeResponse:
    def __init__(self, payload: dict) -> None:
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False

    def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")


class TextGenerationExecutorTest(unittest.TestCase):
    def test_normalizes_cli_text_generation(self):
        target = worker.ExecutionTarget(
            executor_type="CLI",
            executor_id="codex",
            label="Codex CLI",
            protocol="local-cli",
            capabilities=("TEXT_GENERATION",),
            config={"id": "codex", "command": "/opt/homebrew/bin/codex"},
        )
        cli_response = {
            "stdout": '{"items":[]}',
            "stderr": "",
            "returnCode": 0,
            "command": "/opt/homebrew/bin/codex",
            "defaultArgs": ["exec"],
            "effectiveArgs": ["exec"],
            "model": "gpt-test",
            "reasoningEffort": "low",
            "workingDirectory": "/tmp/project",
        }

        with patch.object(worker, "run_cli_prompt", return_value=cli_response):
            result = worker.execute_text_generation(target, "score this")

        self.assertEqual('{"items":[]}', result["rawOutput"])
        self.assertEqual("CLI", result["executorType"])
        self.assertEqual("codex", result["executorId"])
        self.assertEqual("local-cli", result["protocol"])
        self.assertEqual("gpt-test", result["model"])

    def test_calls_openai_compatible_provider_and_hides_key(self):
        target = worker.ExecutionTarget(
            executor_type="AI_PROVIDER",
            executor_id="openai-main",
            label="OpenAI Main",
            protocol="openai-compatible",
            capabilities=("TEXT_GENERATION",),
            config={
                "api_key": "provider-secret",
                "base_url": "https://provider.example/v1",
                "model": "provider-model",
                "max_tokens": 2048,
            },
        )
        response = {"choices": [{"message": {"content": '{"items":[]}'}}]}

        with patch.object(worker.urllib.request, "urlopen", return_value=FakeResponse(response)) as urlopen:
            result = worker.execute_text_generation(target, "score this")

        request = urlopen.call_args.args[0]
        request_payload = json.loads(request.data.decode("utf-8"))
        self.assertEqual("https://provider.example/v1/chat/completions", request.full_url)
        self.assertEqual("provider-model", request_payload["model"])
        self.assertEqual("score this", request_payload["messages"][0]["content"])
        self.assertEqual('{"items":[]}', result["rawOutput"])
        self.assertNotIn("provider-secret", json.dumps(result))

    def test_calls_anthropic_compatible_provider(self):
        target = worker.ExecutionTarget(
            executor_type="AI_PROVIDER",
            executor_id="anthropic-main",
            label="Anthropic Main",
            protocol="anthropic-compatible",
            capabilities=("TEXT_GENERATION",),
            config={
                "api_key": "anthropic-secret",
                "base_url": "https://anthropic.example/v1",
                "model": "claude-test",
                "max_tokens": 1024,
            },
        )
        response = {"content": [{"type": "text", "text": '{"items":[]}' }]}

        with patch.object(worker.urllib.request, "urlopen", return_value=FakeResponse(response)) as urlopen:
            result = worker.execute_text_generation(target, "score this")

        request = urlopen.call_args.args[0]
        self.assertEqual("https://anthropic.example/v1/messages", request.full_url)
        self.assertEqual('{"items":[]}', result["rawOutput"])
        self.assertEqual("anthropic-compatible", result["protocol"])


if __name__ == "__main__":
    unittest.main()
