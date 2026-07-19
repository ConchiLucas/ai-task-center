import asyncio
import importlib.util
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_minio_proxy", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(worker)


def storage_config():
    return worker.ObjectStorageConfig(
        id=7,
        config_name="local",
        provider_type="MINIO",
        endpoint="127.0.0.1:19100",
        access_key="access",
        secret_key="secret",
        use_ssl=False,
        bucket_name="ai-file-navigation",
        base_path="word_clean_tts",
        enabled=True,
        is_default=True,
    )


class FakeObjectResponse:
    def __init__(self, chunks):
        self.chunks = chunks
        self.closed = False
        self.released = False

    def stream(self, _chunk_size):
        yield from self.chunks

    def close(self):
        self.closed = True

    def release_conn(self):
        self.released = True


async def response_bytes(response):
    chunks = []
    async for chunk in response.body_iterator:
        chunks.append(chunk)
    return b"".join(chunks)


def test_streams_configured_minio_wav_and_releases_connection():
    object_response = FakeObjectResponse([b"RIFF", b"WAVE"])
    client = MagicMock()
    client.get_object.return_value = object_response
    with (
        patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
        patch.object(worker, "build_minio_client", return_value=client),
    ):
        response = worker.download_tts_storage_file(
            7,
            "ai-file-navigation",
            "word_clean_tts/word_clean_1_tts_2.wav",
        )
        body = asyncio.run(response_bytes(response))

    assert response.media_type == "audio/wav"
    assert body == b"RIFFWAVE"
    assert object_response.closed is True
    assert object_response.released is True
    client.get_object.assert_called_once_with(
        "ai-file-navigation",
        "word_clean_tts/word_clean_1_tts_2.wav",
    )


@pytest.mark.parametrize(
    ("bucket", "object_key"),
    [
        ("other", "word_clean_tts/word.wav"),
        ("ai-file-navigation", "../secret.wav"),
        ("ai-file-navigation", "other/word.wav"),
    ],
)
def test_rejects_bucket_or_object_key_outside_snapshot(bucket, object_key):
    with (
        patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
        patch.object(worker, "build_minio_client") as build_client,
        pytest.raises(worker.HTTPException) as raised,
    ):
        worker.download_tts_storage_file(7, bucket, object_key)

    assert raised.value.status_code == 400
    build_client.assert_not_called()


def test_returns_404_when_minio_object_is_missing():
    class MissingObject(Exception):
        code = "NoSuchKey"

    client = MagicMock()
    client.get_object.side_effect = MissingObject()
    with (
        patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
        patch.object(worker, "build_minio_client", return_value=client),
        pytest.raises(worker.HTTPException) as raised,
    ):
        worker.download_tts_storage_file(
            7,
            "ai-file-navigation",
            "word_clean_tts/missing.wav",
        )

    assert raised.value.status_code == 404
