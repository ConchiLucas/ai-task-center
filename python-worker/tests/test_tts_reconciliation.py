import copy

import pytest

from app.tts_reconciliation import (
    BestSentenceRepairFact,
    BestSentenceRepairIdentity,
    ObjectFact,
    ReconciliationGuardError,
    TaskConfig4SourceFact,
    build_synced_task_config_4_payload,
    classify_orphan_object,
    validate_best_sentence_repair_set,
    validate_unique_source_ids,
)


EXPECTED_REPAIR = {
    67373: BestSentenceRepairIdentity(160, "acceleration", "FAILED"),
    68521: BestSentenceRepairIdentity(1308, "assailant", "SUCCESS"),
}


def repair_facts():
    return [
        BestSentenceRepairFact(67373, 160, "acceleration", "FAILED", "pending", False, False),
        BestSentenceRepairFact(68521, 1308, "assailant", "SUCCESS", "running", False, False),
    ]


def test_exact_best_sentence_repair_set_accepts_only_expected_missing_objects():
    validate_best_sentence_repair_set(repair_facts(), EXPECTED_REPAIR)


@pytest.mark.parametrize(
    "rows",
    [
        repair_facts()[:1],
        repair_facts() + [BestSentenceRepairFact(1, 1, "extra", "FAILED", "pending", False, False)],
        [repair_facts()[0], BestSentenceRepairFact(68521, 1308, "changed", "SUCCESS", "running", False, False)],
        [repair_facts()[0], BestSentenceRepairFact(68521, 1308, "assailant", "SUCCESS", "running", True, False)],
    ],
)
def test_exact_best_sentence_repair_set_rejects_any_drift(rows):
    with pytest.raises(ReconciliationGuardError):
        validate_best_sentence_repair_set(rows, EXPECTED_REPAIR)


def source_fact(source_id=2, word_clean_id=14153):
    return TaskConfig4SourceFact(
        source_id=source_id,
        word_clean_id=word_clean_id,
        bucket="ai-file-navigation",
        object_key=f"word_clean_tts/word_clean_{word_clean_id}_tts_{source_id}.wav",
        object_url=f"/ai-file-navigation/word_clean_tts/word_clean_{word_clean_id}_tts_{source_id}.wav",
        file_size=20,
    )


def object_fact(source=None):
    source = source or source_fact()
    return ObjectFact(source.bucket, source.object_key, 20, "abc")


def task_config_4_payload():
    return {
        "taskType": "word_clean_tts",
        "wordCleanTtsId": 2,
        "wordCleanId": 14153,
        "word": "painter",
        "ttsResult": {
            "fileName": "word_clean_14153_tts_2.wav",
            "downloadUrl": "http://127.0.0.1:19186/api/tts/files/word_clean_14153_tts_2.wav",
            "provider": "xiaomi-mimo-tts",
            "model": "mimo-v2.5-tts",
            "voice": "Chloe",
        },
        "execution": {"taskRunId": 123},
    }


def test_sync_payload_changes_only_storage_snapshot_and_storage_result_fields():
    original = task_config_4_payload()
    original_copy = copy.deepcopy(original)

    updated = build_synced_task_config_4_payload(
        original,
        source_fact(),
        object_fact(),
        "http://127.0.0.1:19186",
        storage_config_id=1,
        provider_type="MINIO",
        object_prefix="word_clean_tts",
    )

    assert original == original_copy
    assert updated["storageTarget"] == {
        "storageConfigId": 1,
        "providerType": "MINIO",
        "bucket": "ai-file-navigation",
        "objectPrefix": "word_clean_tts",
    }
    assert updated["ttsResult"]["objectKey"] == source_fact().object_key
    assert updated["ttsResult"]["fileSize"] == 20
    assert updated["ttsResult"]["etag"] == "abc"
    assert updated["ttsResult"]["md5"] == "abc"
    assert updated["ttsResult"]["storageVerified"] is True
    assert updated["ttsResult"]["downloadUrl"].startswith(
        "http://127.0.0.1:19186/api/tts/storage/1/ai-file-navigation/"
    )
    assert updated["word"] == original["word"]
    assert updated["execution"] == original["execution"]
    assert updated["ttsResult"]["provider"] == original["ttsResult"]["provider"]


@pytest.mark.parametrize(
    ("source", "obj", "message"),
    [
        (source_fact(source_id=3), object_fact(source_fact(source_id=3)), "来源 ID"),
        (source_fact(), ObjectFact("ai-file-navigation", source_fact().object_key, 21, "abc"), "大小"),
        (source_fact(), ObjectFact("other", source_fact().object_key, 20, "abc"), "Bucket"),
    ],
)
def test_sync_payload_rejects_identity_or_object_mismatch(source, obj, message):
    with pytest.raises(ReconciliationGuardError, match=message):
        build_synced_task_config_4_payload(
            task_config_4_payload(),
            source,
            obj,
            "http://127.0.0.1:19186",
            storage_config_id=1,
            provider_type="MINIO",
            object_prefix="word_clean_tts",
        )


def test_unique_source_guard_rejects_duplicates():
    with pytest.raises(ReconciliationGuardError, match="重复"):
        validate_unique_source_ids([source_fact(), source_fact()])


def test_orphan_classification_is_read_only_and_deterministic():
    historical = classify_orphan_object(
        "word_clean_tts/old.wav",
        current_refs=set(),
        historical_refs={"word_clean_tts/old.wav": [12, 13]},
        current_source_ids={},
    )
    current = classify_orphan_object(
        "word_clean_tts/current.wav",
        current_refs=set(),
        historical_refs={},
        current_source_ids={"word_clean_tts/current.wav": 99},
    )
    unmapped = classify_orphan_object(
        "word_clean_tts/unknown.wav",
        current_refs=set(),
        historical_refs={},
        current_source_ids={},
    )

    assert historical.category == "historical_result"
    assert historical.historical_result_ids == (12, 13)
    assert current.category == "current_source_unreferenced"
    assert current.current_source_id == 99
    assert unmapped.category == "unmapped"


def test_orphan_classifier_rejects_currently_referenced_object():
    with pytest.raises(ReconciliationGuardError, match="仍被当前业务表引用"):
        classify_orphan_object(
            "word_clean_tts/current.wav",
            current_refs={"word_clean_tts/current.wav"},
            historical_refs={},
            current_source_ids={},
        )
