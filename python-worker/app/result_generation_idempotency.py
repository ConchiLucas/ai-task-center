import hashlib
import json
import re
from typing import Any, Callable, Iterable, Optional, Sequence


ONBOARDING_GENERATION_ID_PATTERN = re.compile(r"[0-9a-f]{64}")


def normalize_onboarding_generation_id(value: Optional[str]) -> Optional[str]:
    normalized = "" if value is None else value.strip()
    if not normalized:
        return None
    if ONBOARDING_GENERATION_ID_PATTERN.fullmatch(normalized) is None:
        raise ValueError("onboardingGenerationId must be 64 lowercase hexadecimal characters")
    return normalized


def advisory_lock_key(task_config_id: int, generation_id: str) -> int:
    normalized = normalize_onboarding_generation_id(generation_id)
    if normalized is None:
        raise ValueError("onboardingGenerationId is required for advisory locking")
    digest = hashlib.sha256(f"{task_config_id}:{normalized}".encode("utf-8")).digest()
    return int.from_bytes(digest[:8], byteorder="big", signed=True)


def execute_onboarding_generation_transaction(
    connection: Any,
    task_config_id: int,
    generation_id: str,
    source_description: str,
    rows_supplier: Callable[[], Sequence[tuple[Any, ...]]],
    insert_rows: Callable[[Any, Sequence[tuple[Any, ...]]], int],
) -> dict[str, Any]:
    normalized = normalize_onboarding_generation_id(generation_id)
    if normalized is None:
        raise ValueError("onboardingGenerationId is required")
    with connection.cursor() as cursor:
        cursor.execute(
            "select pg_advisory_xact_lock(%s)",
            (advisory_lock_key(task_config_id, normalized),),
        )
        cursor.execute(
            """
            select result_content
            from tb_task_result
            where task_config_id = %s
              and source_description = %s
            order by id
            """,
            (task_config_id, source_description),
        )
        recovered = build_recovered_generation_response(
            task_config_id,
            normalized,
            (row[0] for row in cursor.fetchall()),
        )
    if recovered is not None:
        connection.commit()
        return recovered

    rows = list(rows_supplier())
    inserted_count = insert_rows(connection, rows)
    connection.commit()
    return {
        "accepted": True,
        "taskConfigId": task_config_id,
        "insertedCount": inserted_count,
        "recovered": False,
    }


def merge_onboarding_generation_metadata(
    payload: dict[str, Any], generation_id: Optional[str]
) -> dict[str, Any]:
    normalized = normalize_onboarding_generation_id(generation_id)
    merged = dict(payload)
    if normalized is None:
        return merged
    existing_meta = merged.get("_meta")
    if existing_meta is None:
        metadata: dict[str, Any] = {}
    elif isinstance(existing_meta, dict):
        metadata = dict(existing_meta)
    else:
        raise ValueError("result payload _meta must be a JSON object")
    metadata["onboardingGenerationId"] = normalized
    merged["_meta"] = metadata
    return merged


def build_recovered_generation_response(
    task_config_id: int,
    generation_id: str,
    result_contents: Iterable[Optional[str]],
) -> Optional[dict[str, Any]]:
    normalized = normalize_onboarding_generation_id(generation_id)
    if normalized is None:
        return None
    recovered_count = sum(
        1
        for content in result_contents
        if _generation_id_from_content(content) == normalized
    )
    if recovered_count == 0:
        return None
    return {
        "accepted": True,
        "taskConfigId": task_config_id,
        "insertedCount": recovered_count,
        "skippedCount": 0,
        "deletedCount": 0,
        "overwrite": False,
        "recovered": True,
    }


def _generation_id_from_content(content: Optional[str]) -> Optional[str]:
    try:
        payload = json.loads(content or "")
    except (json.JSONDecodeError, TypeError):
        return None
    if not isinstance(payload, dict):
        return None
    metadata = payload.get("_meta")
    if not isinstance(metadata, dict):
        return None
    value = metadata.get("onboardingGenerationId")
    return value if isinstance(value, str) else None
