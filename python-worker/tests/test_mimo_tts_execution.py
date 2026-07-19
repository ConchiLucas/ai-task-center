import base64
import importlib.util
import json
import os
import tempfile
import unittest
import urllib.error
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from unittest.mock import MagicMock, patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_mimo_tts", MODULE_PATH)
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


class FakeResponse:
    def __init__(self, payload: dict) -> None:
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False

    def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")


class MimoTtsExecutionTest(unittest.TestCase):
    def test_loads_snake_case_mimo_provider_from_database(self):
        providers = {
            "xiaomi-mimo-tts": {
                "id": "xiaomi-mimo-tts",
                "api_key": "database-secret",
                "base_url": "https://database.example/v1",
                "model": "database-model",
            }
        }
        connection = database_connection(("xiaomi-mimo-tts", json.dumps(providers)))

        with patch.object(worker, "connect_database", return_value=connection):
            config = worker.load_mimo_tts_config()

        self.assertEqual("database-secret", config.api_key)
        self.assertEqual("https://database.example/v1", config.base_url)
        self.assertEqual("database-model", config.model)
        self.assertEqual("database", config.source)

    def test_falls_back_to_environment_when_database_is_unavailable(self):
        with (
            patch.object(worker, "connect_database", side_effect=RuntimeError("database unavailable")),
            patch.dict(
                os.environ,
                {
                    "MIMO_API_KEY": "environment-secret",
                    "MIMO_TTS_BASE_URL": "https://environment.example/v1",
                    "MIMO_TTS_MODEL": "environment-model",
                },
                clear=False,
            ),
        ):
            config = worker.load_mimo_tts_config()

        self.assertEqual("environment-secret", config.api_key)
        self.assertEqual("https://environment.example/v1", config.base_url)
        self.assertEqual("environment-model", config.model)
        self.assertEqual("environment", config.source)

    def test_generates_audio_inside_python_worker(self):
        audio = b"RIFF\x10\x00\x00\x00WAVEfmt "
        response = {
            "choices": [
                {"message": {"audio": {"data": base64.b64encode(audio).decode("ascii")}}}
            ]
        }
        config = worker.MiMoTTSConfig(
            provider_id="xiaomi-mimo-tts",
            api_key="database-secret",
            base_url="https://database.example/v1",
            model="database-model",
            voice="Chloe",
            source="database",
        )

        with tempfile.TemporaryDirectory() as directory:
            with (
                patch.object(worker, "TTS_OUTPUT_DIR", Path(directory)),
                patch.object(worker, "load_mimo_tts_config", return_value=config),
                patch.object(
                    worker.urllib.request,
                    "urlopen",
                    return_value=FakeResponse(response),
                ) as urlopen,
            ):
                result = worker.generate_mimo_tts(
                    {
                        "text": "This is an example.",
                        "fileName": "example.wav",
                        "defaultAudioFormat": "wav",
                    }
                )
                saved_audio = (Path(directory) / "example.wav").read_bytes()

        self.assertEqual(audio, saved_audio)
        self.assertEqual("xiaomi-mimo-tts", result["provider"])
        self.assertEqual("database-model", result["model"])
        self.assertEqual("http://127.0.0.1:19186/api/tts/files/example.wav", result["downloadUrl"])
        request = urlopen.call_args.args[0]
        self.assertEqual("https://database.example/v1/chat/completions", request.full_url)
        self.assertNotIn("database-secret", str(result))

    def test_generates_validated_audio_bytes_without_writing_local_file(self):
        audio = b"RIFF\x10\x00\x00\x00WAVEfmt "
        response = {
            "choices": [{"message": {"audio": {"data": base64.b64encode(audio).decode("ascii")}}}]
        }
        config = worker.MiMoTTSConfig(
            provider_id="xiaomi-mimo-tts",
            api_key="database-secret",
            base_url="https://database.example/v1",
            model="database-model",
            voice="Chloe",
            source="database",
        )
        with tempfile.TemporaryDirectory() as directory:
            with (
                patch.object(worker, "TTS_OUTPUT_DIR", Path(directory)),
                patch.object(worker, "load_mimo_tts_config", return_value=config),
                patch.object(worker.urllib.request, "urlopen", return_value=FakeResponse(response)),
            ):
                generated = worker.generate_mimo_tts_audio({
                    "text": "example",
                    "fileName": "example.wav",
                    "defaultAudioFormat": "wav",
                })
                files = list(Path(directory).iterdir())

        self.assertEqual(audio, generated.audio_bytes)
        self.assertEqual("example.wav", generated.file_name)
        self.assertEqual([], files)
        self.assertNotIn("database-secret", repr(generated))

    def test_retry_after_supports_seconds_and_http_date(self):
        now = datetime(2026, 7, 19, 9, 0, 0, tzinfo=timezone.utc)

        self.assertEqual(3.0, worker.parse_retry_after_seconds("3", now))
        self.assertEqual(
            5.0,
            worker.parse_retry_after_seconds("Sun, 19 Jul 2026 09:00:05 GMT", now),
        )

    def test_429_uses_bounded_retry_and_stops_after_max_attempts(self):
        config = worker.MiMoTTSConfig(
            provider_id="xiaomi-mimo-tts",
            api_key="database-secret",
            base_url="https://database.example/v1",
            model="database-model",
            voice="Chloe",
            source="database",
        )

        def rate_limit():
            return urllib.error.HTTPError(
                "https://database.example/v1/chat/completions",
                429,
                "Too Many Requests",
                {},
                BytesIO(b'{"error":"rate limited"}'),
            )

        sleeps = MagicMock()
        with (
            patch.object(worker, "load_mimo_tts_config", return_value=config),
            patch.object(worker.urllib.request, "urlopen", side_effect=[rate_limit(), rate_limit(), rate_limit()]),
            self.assertRaises(worker.HTTPException) as raised,
        ):
            worker.generate_mimo_tts_audio(
                {"text": "example", "fileName": "example.wav", "defaultAudioFormat": "wav"},
                max_attempts=3,
                sleep=sleeps,
            )

        self.assertEqual(502, raised.exception.status_code)
        self.assertIn("HTTP 429", raised.exception.detail)
        self.assertEqual([((1.0,), {}), ((2.0,), {})], sleeps.call_args_list)

    def test_rejects_unsafe_tts_file_name(self):
        with self.assertRaises(worker.HTTPException) as raised:
            worker.resolve_tts_file_path("../secret.wav")

        self.assertEqual(400, raised.exception.status_code)


if __name__ == "__main__":
    unittest.main()
