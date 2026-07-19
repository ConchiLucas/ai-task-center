from __future__ import annotations

import copy
from dataclasses import dataclass
from typing import Any, Mapping, Sequence


class ReconciliationGuardError(ValueError):
    """Raised before reconciliation when the live facts drift from the audited set."""


@dataclass(frozen=True)
class BestSentenceRepairIdentity:
    best_sentence_id: int
    word: str
    task_result_status: str


@dataclass(frozen=True)
class BestSentenceRepairFact:
    task_result_id: int
    best_sentence_id: int
    word: str
    task_result_status: str
    source_status: str
    minio_object_exists: bool
    local_file_exists: bool


@dataclass(frozen=True)
class ObjectFact:
    bucket: str
    object_key: str
    size: int
    etag: str


@dataclass(frozen=True)
class TaskConfig4SourceFact:
    source_id: int
    word_clean_id: int
    bucket: str
    object_key: str
    object_url: str
    file_size: int


@dataclass(frozen=True)
class OrphanClassification:
    object_key: str
    category: str
    historical_result_ids: tuple[int, ...] = ()
    current_source_id: int | None = None


def validate_best_sentence_repair_set(
    rows: Sequence[BestSentenceRepairFact],
    expected: Mapping[int, BestSentenceRepairIdentity],
) -> None:
    actual_by_result_id = {row.task_result_id: row for row in rows}
    if len(actual_by_result_id) != len(rows):
        raise ReconciliationGuardError("精确修复集合包含重复任务结果 ID")
    if set(actual_by_result_id) != set(expected):
        raise ReconciliationGuardError("精确修复集合 ID 与已审计基线不一致")
    for task_result_id, identity in expected.items():
        row = actual_by_result_id[task_result_id]
        if (
            row.best_sentence_id != identity.best_sentence_id
            or row.word != identity.word
            or row.task_result_status != identity.task_result_status
        ):
            raise ReconciliationGuardError(f"精确修复记录身份已变化: {task_result_id}")
        if row.source_status not in {"pending", "running"}:
            raise ReconciliationGuardError(f"精确修复来源状态不可执行: {row.best_sentence_id}")
        if row.minio_object_exists or row.local_file_exists:
            raise ReconciliationGuardError(f"精确修复对象已存在: {row.best_sentence_id}")


def validate_unique_source_ids(rows: Sequence[TaskConfig4SourceFact]) -> None:
    source_ids = [row.source_id for row in rows]
    if len(source_ids) != len(set(source_ids)):
        raise ReconciliationGuardError("任务配置 4 来源 ID 重复")


def build_synced_task_config_4_payload(
    payload: Mapping[str, Any],
    source: TaskConfig4SourceFact,
    object_stat: ObjectFact,
    proxy_base_url: str,
    *,
    storage_config_id: int,
    provider_type: str,
    object_prefix: str,
) -> dict[str, Any]:
    if str(payload.get("taskType") or "") != "word_clean_tts":
        raise ReconciliationGuardError("任务结果类型不是 word_clean_tts")
    if int(payload.get("wordCleanTtsId") or 0) != source.source_id:
        raise ReconciliationGuardError("任务结果与来源 ID 不一致")
    if int(payload.get("wordCleanId") or 0) != source.word_clean_id:
        raise ReconciliationGuardError("任务结果与来源 wordCleanId 不一致")
    if object_stat.bucket != source.bucket:
        raise ReconciliationGuardError("MinIO Bucket 与来源记录不一致")
    if object_stat.object_key != source.object_key:
        raise ReconciliationGuardError("MinIO object key 与来源记录不一致")
    if object_stat.size != source.file_size:
        raise ReconciliationGuardError("MinIO 对象大小与来源记录不一致")
    normalized_etag = str(object_stat.etag or "").strip().strip('"').lower()
    if object_stat.size <= 0 or not normalized_etag:
        raise ReconciliationGuardError("MinIO 对象校验事实不完整")
    if int(storage_config_id) <= 0 or str(provider_type or "").strip().upper() != "MINIO":
        raise ReconciliationGuardError("对象存储配置快照无效")

    updated = copy.deepcopy(dict(payload))
    updated["storageTarget"] = {
        "storageConfigId": int(storage_config_id),
        "providerType": "MINIO",
        "bucket": source.bucket,
        "objectPrefix": str(object_prefix).strip().strip("/"),
    }
    old_tts_result = updated.get("ttsResult")
    if not isinstance(old_tts_result, dict):
        old_tts_result = {}
    updated["ttsResult"] = {
        **old_tts_result,
        "bucket": source.bucket,
        "objectKey": source.object_key,
        "objectUrl": source.object_url,
        "downloadUrl": (
            f"{str(proxy_base_url).rstrip('/')}/api/tts/storage/"
            f"{int(storage_config_id)}/{source.bucket}/{source.object_key}"
        ),
        "byteSize": object_stat.size,
        "fileSize": object_stat.size,
        "md5": normalized_etag,
        "etag": normalized_etag,
        "objectReused": True,
        "storageVerified": True,
    }
    return updated


def classify_orphan_object(
    object_key: str,
    current_refs: set[str],
    historical_refs: Mapping[str, Sequence[int]],
    current_source_ids: Mapping[str, int],
) -> OrphanClassification:
    if object_key in current_refs:
        raise ReconciliationGuardError("对象仍被当前业务表引用，不能归类为孤立对象")
    historical_result_ids = tuple(sorted({int(item) for item in historical_refs.get(object_key, ())}))
    if historical_result_ids:
        return OrphanClassification(
            object_key=object_key,
            category="historical_result",
            historical_result_ids=historical_result_ids,
        )
    current_source_id = current_source_ids.get(object_key)
    if current_source_id is not None:
        return OrphanClassification(
            object_key=object_key,
            category="current_source_unreferenced",
            current_source_id=int(current_source_id),
        )
    return OrphanClassification(object_key=object_key, category="unmapped")
