import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_execution_targets", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(worker)


def database_connection(row):
    cursor = MagicMock()
    cursor.fetchone.return_value = row
    cursor_context = MagicMock()
    cursor_context.__enter__.return_value = cursor
    connection = MagicMock()
    connection.cursor.return_value = cursor_context
    connection_context = MagicMock()
    connection_context.__enter__.return_value = connection
    return connection_context


class ExecutionTargetResolutionTest(unittest.TestCase):
    def test_runtime_target_comes_from_strict_snapshot(self):
        task_run = worker.TaskRunSnapshot(
            id=31,
            task_name="评分批次",
            ai_prompt_json="{}",
            ai_response_json="",
            record_type=worker.RECORD_TYPE_FORMAL,
            requested_worker_count=1,
            handler_key=worker.HANDLER_SCORE,
            executor_type="CLI",
            executor_id="antigravity",
        )
        target = worker.ExecutionTarget(
            executor_type="CLI",
            executor_id="antigravity",
            label="Antigravity CLI",
            protocol="local-cli",
            capabilities=("TEXT_GENERATION",),
            config={},
        )

        with patch.object(worker, "resolve_execution_target", return_value=target) as resolve_target:
            resolved = worker.resolve_task_run_text_target(task_run)

        self.assertEqual("antigravity", resolved.executor_id)
        resolve_target.assert_called_once_with("CLI", "antigravity", "TEXT_GENERATION")

    def test_resolves_mimo_provider_with_tts_capability_without_exposing_key(self):
        providers = {
            "xiaomi-mimo-tts": {
                "id": "xiaomi-mimo-tts",
                "label": "小米 MiMo TTS",
                "type": "mimo-tts",
                "api_key": "database-secret",
                "base_url": "https://database.example/v1",
                "model": "mimo-v2.5-tts",
                "voice": "Chloe",
                "enabled": True,
                "capabilities": ["AUDIO_TTS"],
            }
        }
        connection = database_connection((json.dumps(providers), json.dumps({"configs": []})))

        with patch.object(worker, "connect_database", return_value=connection):
            target = worker.resolve_execution_target(
                "AI_PROVIDER",
                "xiaomi-mimo-tts",
                "AUDIO_TTS",
            )

        self.assertEqual("AI_PROVIDER", target.executor_type)
        self.assertEqual("xiaomi-mimo-tts", target.executor_id)
        self.assertEqual("mimo-tts", target.protocol)
        self.assertEqual(("AUDIO_TTS",), target.capabilities)
        self.assertNotIn("database-secret", json.dumps(target.public_metadata()))

    def test_rejects_cli_without_tts_capability(self):
        cli_payload = {
            "active": "codex",
            "configs": [
                {
                    "id": "codex",
                    "label": "Codex CLI",
                    "command": "/opt/homebrew/bin/codex",
                    "enabled": True,
                    "capabilities": ["TEXT_GENERATION", "CODE_EXECUTION"],
                }
            ],
        }
        connection = database_connection((json.dumps({}), json.dumps(cli_payload)))

        with (
            patch.object(worker, "connect_database", return_value=connection),
            self.assertRaises(worker.HTTPException) as raised,
        ):
            worker.resolve_execution_target("CLI", "codex", "AUDIO_TTS")

        self.assertEqual(400, raised.exception.status_code)
        self.assertIn("AUDIO_TTS", str(raised.exception.detail))

    def test_mimo_config_uses_requested_provider_id(self):
        providers = {
            "custom-mimo": {
                "id": "custom-mimo",
                "type": "mimo-tts",
                "api_key": "database-secret",
                "base_url": "https://custom.example/v1",
                "model": "custom-model",
                "voice": "CustomVoice",
            }
        }
        connection = database_connection(("custom-mimo", json.dumps(providers)))

        with patch.object(worker, "connect_database", return_value=connection):
            config = worker.load_mimo_tts_config("custom-mimo")

        self.assertEqual("custom-mimo", config.provider_id)
        self.assertEqual("https://custom.example/v1", config.base_url)

    def test_strict_mimo_provider_never_falls_back_to_environment(self):
        with (
            patch.object(
                worker,
                "resolve_execution_target",
                side_effect=worker.HTTPException(status_code=400, detail="provider missing"),
            ),
            patch.dict(worker.os.environ, {"MIMO_API_KEY": "environment-secret"}, clear=False),
            self.assertRaises(worker.HTTPException) as raised,
        ):
            worker.load_mimo_tts_config("deleted-mimo", strict_provider=True)

        self.assertEqual(400, raised.exception.status_code)
        self.assertIn("provider missing", str(raised.exception.detail))


if __name__ == "__main__":
    unittest.main()
