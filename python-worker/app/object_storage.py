from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class ObjectStorageConfig:
    id: int
    config_name: str
    provider_type: str
    endpoint: str
    access_key: str = ""
    secret_key: str = ""
    use_ssl: bool = False
    bucket_name: str = ""
    base_path: str = ""
    enabled: bool = False
    is_default: bool = False

    def __repr__(self) -> str:
        return (
            "ObjectStorageConfig("
            f"id={self.id!r}, config_name={self.config_name!r}, provider_type={self.provider_type!r}, "
            f"endpoint={self.endpoint!r}, access_key_configured={bool(self.access_key)!r}, "
            f"secret_key_configured={bool(self.secret_key)!r}, use_ssl={self.use_ssl!r}, "
            f"bucket_name={self.bucket_name!r}, base_path={self.base_path!r}, "
            f"enabled={self.enabled!r}, is_default={self.is_default!r})"
        )


@dataclass(frozen=True)
class StorageTarget:
    storage_config_id: int
    provider_type: str
    bucket: str
    object_prefix: str

    def as_payload(self) -> dict[str, Any]:
        return {
            "storageConfigId": self.storage_config_id,
            "providerType": self.provider_type,
            "bucket": self.bucket,
            "objectPrefix": self.object_prefix,
        }


@dataclass(frozen=True)
class StoredObject:
    bucket: str
    object_key: str
    object_url: str
    byte_size: int
    md5: str
    etag: str
    reused: bool


def object_storage_config_from_row(row: tuple[Any, ...]) -> ObjectStorageConfig:
    if not row or len(row) < 11:
        raise ValueError("对象存储配置记录不完整")
    return ObjectStorageConfig(
        id=int(row[0]),
        config_name=str(row[1] or "").strip(),
        provider_type=str(row[2] or "").strip().upper(),
        endpoint=str(row[3] or "").strip(),
        access_key=str(row[4] or "").strip(),
        secret_key=str(row[5] or "").strip(),
        use_ssl=bool(row[6]),
        bucket_name=clean_path(str(row[7] or "")),
        base_path=clean_path(str(row[8] or "")),
        enabled=bool(row[9]),
        is_default=bool(row[10]),
    )


def parse_storage_target(value: Any) -> StorageTarget:
    if not isinstance(value, dict):
        raise ValueError("TTS 结果缺少对象存储快照")
    try:
        storage_config_id = int(value["storageConfigId"])
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError("对象存储快照缺少有效 storageConfigId") from exc
    provider_type = str(value.get("providerType") or "").strip().upper()
    bucket = clean_path(str(value.get("bucket") or ""))
    object_prefix = clean_path(str(value.get("objectPrefix") or ""))
    if provider_type != "MINIO":
        raise ValueError("对象存储快照仅支持 MINIO")
    if not bucket:
        raise ValueError("对象存储快照缺少 Bucket")
    if not object_prefix:
        raise ValueError("对象存储快照缺少对象前缀")
    return StorageTarget(storage_config_id, provider_type, bucket, object_prefix)


def validate_storage_target(target: StorageTarget, config: ObjectStorageConfig) -> None:
    if not config.enabled:
        raise ValueError("对象存储配置已停用")
    if config.provider_type != "MINIO":
        raise ValueError("对象存储配置类型不是 MINIO")
    if not config.endpoint or not config.access_key or not config.secret_key:
        raise ValueError("对象存储配置凭据不完整")
    if target.storage_config_id != config.id:
        raise ValueError("对象存储配置 ID 与任务快照不一致")
    if target.provider_type != config.provider_type:
        raise ValueError("对象存储类型与任务快照不一致")
    if target.bucket != config.bucket_name:
        raise ValueError("对象存储 Bucket 与任务快照不一致")
    if target.object_prefix != config.base_path:
        raise ValueError("对象存储前缀与任务快照不一致")


def validate_wav_bytes(audio: bytes) -> None:
    if len(audio) < 12 or audio[:4] != b"RIFF" or audio[8:12] != b"WAVE":
        raise ValueError("MiMo TTS 返回的音频不是有效 WAV")


def build_object_key(prefix: str, file_name: str) -> str:
    normalized_name = str(file_name or "").strip()
    if (
        not normalized_name
        or normalized_name != Path(normalized_name).name
        or not re.fullmatch(r"[A-Za-z0-9._-]+\.wav", normalized_name, re.IGNORECASE)
    ):
        raise ValueError("TTS 对象文件名无效")
    normalized_prefix = clean_path(prefix)
    if not normalized_prefix or ".." in normalized_prefix.split("/"):
        raise ValueError("TTS 对象存储前缀无效")
    return f"{normalized_prefix}/{normalized_name}"


def store_verified_wav(
    client: Any,
    config: ObjectStorageConfig,
    file_name: str,
    audio: bytes,
) -> StoredObject:
    validate_wav_bytes(audio)
    object_key = build_object_key(config.base_path, file_name)
    content_md5 = hashlib.md5(audio).hexdigest()
    existing = _stat_or_none(client, config.bucket_name, object_key)
    if existing is not None and _stat_matches(existing, len(audio), content_md5):
        return _stored_object(config.bucket_name, object_key, len(audio), content_md5, existing, True)

    client.put_object(
        config.bucket_name,
        object_key,
        BytesIO(audio),
        length=len(audio),
        content_type="audio/wav",
    )
    verified = client.stat_object(config.bucket_name, object_key)
    if int(getattr(verified, "size", -1)) != len(audio):
        raise RuntimeError("MinIO 对象大小校验失败")
    verified_etag = normalize_etag(getattr(verified, "etag", ""))
    if verified_etag != content_md5:
        raise RuntimeError("MinIO 对象 ETag/MD5 校验失败")
    return _stored_object(config.bucket_name, object_key, len(audio), content_md5, verified, False)


def build_minio_client(config: ObjectStorageConfig) -> Any:
    from minio import Minio

    return Minio(
        config.endpoint,
        access_key=config.access_key,
        secret_key=config.secret_key,
        secure=config.use_ssl,
    )


def clean_path(value: str) -> str:
    return value.strip().strip("/")


def normalize_etag(value: Any) -> str:
    return str(value or "").strip().strip('"').lower()


def _stat_or_none(client: Any, bucket: str, object_key: str) -> Any | None:
    try:
        return client.stat_object(bucket, object_key)
    except Exception as exc:
        if str(getattr(exc, "code", "")) in {"NoSuchKey", "NoSuchObject", "NoSuchBucket"}:
            return None
        raise


def _stat_matches(stat: Any, expected_size: int, expected_md5: str) -> bool:
    return (
        int(getattr(stat, "size", -1)) == expected_size
        and normalize_etag(getattr(stat, "etag", "")) == expected_md5
    )


def _stored_object(
    bucket: str,
    object_key: str,
    byte_size: int,
    content_md5: str,
    stat: Any,
    reused: bool,
) -> StoredObject:
    return StoredObject(
        bucket=bucket,
        object_key=object_key,
        object_url=f"/{bucket}/{object_key}",
        byte_size=byte_size,
        md5=content_md5,
        etag=normalize_etag(getattr(stat, "etag", "")),
        reused=reused,
    )
