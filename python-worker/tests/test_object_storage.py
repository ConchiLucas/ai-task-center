from dataclasses import replace
from io import BytesIO
from types import SimpleNamespace

import pytest

from app.object_storage import (
    ObjectStorageConfig,
    StorageTarget,
    build_object_key,
    object_storage_config_from_row,
    parse_storage_target,
    store_verified_wav,
    validate_storage_target,
    validate_wav_bytes,
)


WAV_BYTES = b"RIFF\x10\x00\x00\x00WAVEfmt "


def config() -> ObjectStorageConfig:
    return ObjectStorageConfig(
        id=7,
        config_name="local-minio",
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


def test_maps_enabled_database_config_without_exposing_secret_in_repr():
    mapped = object_storage_config_from_row(
        (7, "local-minio", "MINIO", "127.0.0.1:19100", "access", "secret", False,
         "ai-file-navigation", "word_clean_tts", True, True)
    )

    assert mapped == config()
    assert "'secret'" not in repr(mapped)


@pytest.mark.parametrize("audio", [b"", b"not-wave", b"RIFF1234NOPE"])
def test_rejects_invalid_wav_bytes(audio: bytes):
    with pytest.raises(ValueError, match="WAV"):
        validate_wav_bytes(audio)


def test_parses_and_strictly_validates_storage_target():
    target = parse_storage_target({
        "storageConfigId": 7,
        "providerType": "MINIO",
        "bucket": "ai-file-navigation",
        "objectPrefix": "word_clean_tts",
    })

    assert target == StorageTarget(7, "MINIO", "ai-file-navigation", "word_clean_tts")
    validate_storage_target(target, config())

    with pytest.raises(ValueError, match="Bucket"):
        validate_storage_target(replace(target, bucket="other"), config())


@pytest.mark.parametrize("file_name", ["../escape.wav", "nested/file.wav", "bad name.wav"])
def test_rejects_unsafe_object_file_names(file_name: str):
    with pytest.raises(ValueError, match="文件名"):
        build_object_key("word_clean_tts", file_name)


class NotFoundError(Exception):
    code = "NoSuchKey"


class FakeMinio:
    def __init__(self, objects=None):
        self.objects = dict(objects or {})
        self.put_calls = []

    def stat_object(self, bucket: str, object_key: str):
        try:
            payload = self.objects[(bucket, object_key)]
        except KeyError as exc:
            raise NotFoundError() from exc
        return SimpleNamespace(size=len(payload), etag=__import__("hashlib").md5(payload).hexdigest())

    def put_object(self, bucket: str, object_key: str, data: BytesIO, length: int, content_type: str):
        payload = data.read(length)
        self.objects[(bucket, object_key)] = payload
        self.put_calls.append((bucket, object_key, payload, content_type))


def test_uploads_missing_wav_and_verifies_object_metadata():
    client = FakeMinio()

    result = store_verified_wav(client, config(), "word_clean_7_tts_9.wav", WAV_BYTES)

    assert result.object_key == "word_clean_tts/word_clean_7_tts_9.wav"
    assert result.object_url == "/ai-file-navigation/word_clean_tts/word_clean_7_tts_9.wav"
    assert result.byte_size == len(WAV_BYTES)
    assert result.etag == result.md5
    assert result.reused is False
    assert len(client.put_calls) == 1


def test_reuses_identical_object_without_upload():
    key = ("ai-file-navigation", "word_clean_tts/word_clean_7_tts_9.wav")
    client = FakeMinio({key: WAV_BYTES})

    result = store_verified_wav(client, config(), "word_clean_7_tts_9.wav", WAV_BYTES)

    assert result.reused is True
    assert client.put_calls == []


def test_overwrites_mismatched_object_and_verifies_replacement():
    key = ("ai-file-navigation", "word_clean_tts/word_clean_7_tts_9.wav")
    client = FakeMinio({key: WAV_BYTES + b"old"})

    result = store_verified_wav(client, config(), "word_clean_7_tts_9.wav", WAV_BYTES)

    assert result.reused is False
    assert client.objects[key] == WAV_BYTES
    assert len(client.put_calls) == 1


def test_rejects_post_upload_etag_mismatch():
    class BadStatMinio(FakeMinio):
        def stat_object(self, bucket: str, object_key: str):
            stat = super().stat_object(bucket, object_key)
            return SimpleNamespace(size=stat.size, etag="wrong")

    with pytest.raises(RuntimeError, match="ETag"):
        store_verified_wav(BadStatMinio(), config(), "word_clean_7_tts_9.wav", WAV_BYTES)
