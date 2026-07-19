import importlib.util
import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_task_config_4", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(worker)

STORAGE_TARGET = {
    "storageConfigId": 7,
    "providerType": "MINIO",
    "bucket": "ai-file-navigation",
    "objectPrefix": "word_clean_tts",
}


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


def task_config(
    *,
    task_config_id: int = 4,
    handler_key: str = "task_config_4",
    executor_type: str = "AI_PROVIDER",
    executor_id: str = "xiaomi-mimo-tts",
    selected_tables: str = '["public.word_clean_tts"]',
):
    return worker.TaskConfigSnapshot(
        id=task_config_id,
        task_name="单个单词TTS语音生成",
        project_id=1,
        database_config_id=1,
        selected_tables=selected_tables,
        handler_key=handler_key,
        executor_type=executor_type,
        executor_id=executor_id,
    )


def task_result():
    payload = {
        "taskType": "word_clean_tts",
        "wordCleanTtsId": 2,
        "wordCleanId": 14153,
        "word": "painter",
        "sourceTable": "public.word_clean_tts",
        "storageTarget": STORAGE_TARGET,
        "ttsInput": {
            "text": "painter",
            "fileName": "word_clean_14153_tts_2.wav",
            "defaultAudioFormat": "wav",
        },
        "writeBack": {
            "table": "public.word_clean_tts",
            "match": {"id": 2, "word_clean_id": 14153, "word": "painter"},
        },
    }
    return worker.TaskResultSnapshot(
        id=99,
        result_name="单个单词TTS语音生成 - painter (14153)",
        task_config_id=4,
        project_id=1,
        database_config_id=1,
        status="PENDING",
        record_type=worker.RECORD_TYPE_VALIDATION_CURRENT,
        result_content=json.dumps(payload),
        handler_key="task_config_4",
        executor_type="AI_PROVIDER",
        executor_id="xiaomi-mimo-tts",
    )


def test_task_config_4_is_registered_for_result_and_single_tts_validation():
    descriptor = worker.TASK_HANDLER_REGISTRY.describe("task_config_4")

    assert descriptor == {
        "handlerKey": "task_config_4",
        "requiredCapability": "AUDIO_TTS",
        "supportsResultGeneration": True,
        "supportsSingleValidation": True,
        "supportsBatchBuild": True,
        "supportsBatchExecution": True,
    }


def test_fetches_only_pending_nonblank_words_from_real_source_contract():
    cursor = MagicMock()
    cursor.fetchall.return_value = [(2, 14153, "painter", "pending")]
    cursor_context = MagicMock()
    cursor_context.__enter__.return_value = cursor
    connection = MagicMock()
    connection.cursor.return_value = cursor_context
    connection_context = MagicMock()
    connection_context.__enter__.return_value = connection

    with patch.object(worker, "connect_source_database", return_value=connection_context):
        rows = worker.fetch_task_config_4_word_tts_sources(MagicMock())

    query = " ".join(cursor.execute.call_args.args[0].lower().split())
    assert "from public.word_clean_tts" in query
    assert "status = 'pending'" in query
    assert "btrim(word) <> ''" in query
    assert rows == [{
        "wordCleanTtsId": 2,
        "wordCleanId": 14153,
        "word": "painter",
        "status": "pending",
    }]


def test_builds_word_tts_json_and_strict_handler_target_snapshot():
    rows = worker.build_task_config_4_result_rows(
        task_config(),
        ["public.word_clean_tts"],
        [{"wordCleanTtsId": 2, "wordCleanId": 14153, "word": "painter", "status": "pending"}],
        set(),
        worker.RECORD_TYPE_VALIDATION_CURRENT,
        STORAGE_TARGET,
    )

    assert len(rows) == 1
    row = rows[0]
    payload = json.loads(row[12])
    assert row[4:7] == ("task_config_4", "AI_PROVIDER", "xiaomi-mimo-tts")
    assert row[9] == "task_config_4_word_clean_tts_generation"
    assert row[10] == "PENDING"
    assert row[-1] == worker.RECORD_TYPE_VALIDATION_CURRENT
    assert payload["taskType"] == "word_clean_tts"
    assert payload["wordCleanTtsId"] == 2
    assert payload["wordCleanId"] == 14153
    assert payload["word"] == "painter"
    assert payload["storageTarget"] == STORAGE_TARGET
    assert payload["ttsInput"] == {
        "text": "painter",
        "fileName": "word_clean_14153_tts_2.wav",
        "defaultAudioFormat": "wav",
    }
    assert payload["writeBack"]["match"] == {
        "id": 2,
        "word_clean_id": 14153,
        "word": "painter",
        "status": "pending",
    }
    assert payload["writeBack"]["ttsResultMapping"]["status"] == "success"
    assert payload["writeBack"]["ttsResultMapping"]["provider"] == "ttsResult.provider"


def test_audio_filename_uses_ids_instead_of_untrusted_word_text():
    payload = worker.build_task_config_4_result_payload(
        {
            "wordCleanTtsId": 8,
            "wordCleanId": 21,
            "word": "and/or",
            "status": "pending",
        },
        STORAGE_TARGET,
    )

    assert payload["ttsInput"]["text"] == "and/or"
    assert payload["ttsInput"]["fileName"] == "word_clean_21_tts_8.wav"


@pytest.mark.parametrize(
    ("config", "message"),
    [
        (task_config(task_config_id=5), "任务配置 ID 4"),
        (task_config(handler_key="word_clean_best_sentence_tts"), "task_config_4"),
        (task_config(executor_type="CLI", executor_id="codex"), "AI_PROVIDER"),
        (task_config(selected_tables='["public.word_clean_best_sentence"]'), "public.word_clean_tts"),
    ],
)
def test_rejects_nonmatching_handler_model_or_source_snapshot(config, message):
    with pytest.raises(worker.HTTPException) as raised:
        worker.validate_task_config_4_result_snapshot(config)

    assert raised.value.status_code == 400
    assert message in raised.value.detail


def test_single_validation_uses_strict_mimo_target_without_source_writeback():
    result = task_result()
    generated = {
        "provider": "xiaomi-mimo-tts",
        "model": "mimo-v2-tts",
        "voice": "default",
        "format": "wav",
        "fileName": "word_clean_14153_tts_2.wav",
        "byteSize": 42,
    }

    with (
        patch.object(worker, "load_task_result_snapshot", return_value=result),
        patch.object(worker, "generate_mimo_tts", return_value=generated) as generate_tts,
        patch.object(worker, "update_task_result_state") as update_state,
        patch.object(worker, "connect_source_database") as source_connection,
        patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
    ):
        response = worker.process_task_config_4_tts_validation_result(99)

    generate_tts.assert_called_once_with(
        json.loads(result.result_content)["ttsInput"],
        "xiaomi-mimo-tts",
        strict_provider=True,
    )
    source_connection.assert_not_called()
    assert response["status"] == "SUCCESS"
    assert update_state.call_args_list[-1].args[1] == "SUCCESS"
    completed_payload = update_state.call_args_list[-1].args[3]
    assert completed_payload["validationExecution"]["sourceWriteBackSkipped"] is True
