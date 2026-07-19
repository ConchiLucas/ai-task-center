import importlib.util
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_task_config_4_batch", MODULE_PATH)
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


def result_snapshot(
    result_id: int = 41,
    source_id: int = 2,
    word_clean_id: int = 14153,
    word: str = "painter",
):
    payload = {
        "taskType": worker.TASK_CONFIG_4_RESULT_MODE,
        "wordCleanTtsId": source_id,
        "wordCleanId": word_clean_id,
        "word": word,
        "sourceTable": worker.TASK_CONFIG_4_SOURCE_TABLE,
        "storageTarget": STORAGE_TARGET,
        "source": {
            "id": source_id,
            "wordCleanId": word_clean_id,
            "word": word,
            "status": "pending",
        },
        "ttsInput": {
            "text": word,
            "fileName": f"word_clean_{word_clean_id}_tts_{source_id}.wav",
            "defaultAudioFormat": "wav",
        },
        "writeBack": {
            "table": worker.TASK_CONFIG_4_SOURCE_TABLE,
            "match": {
                "id": source_id,
                "word_clean_id": word_clean_id,
                "word": word,
                "status": "pending",
            },
        },
    }
    return worker.TaskResultSnapshot(
        id=result_id,
        result_name=f"单词 TTS {word}",
        task_config_id=4,
        project_id=1,
        database_config_id=1,
        status="PENDING",
        record_type=worker.RECORD_TYPE_FORMAL,
        result_content=json.dumps(payload),
        handler_key=worker.TASK_CONFIG_4_HANDLER,
        executor_type=worker.TASK_CONFIG_4_EXECUTOR_TYPE,
        executor_id=worker.TASK_CONFIG_4_EXECUTOR_ID,
    )


def run_snapshot(record_type=worker.RECORD_TYPE_FORMAL):
    prompt = {
        "taskType": "word_clean_tts_batch",
        "version": 1,
        "batch": {
            "taskConfigId": 4,
            "executionTarget": {
                "executorType": "AI_PROVIDER",
                "executorId": "xiaomi-mimo-tts",
            },
            "storageTarget": STORAGE_TARGET,
        },
        "items": [
            {"itemKey": "item_A", "wordCleanTtsId": 2, "wordCleanId": 14153, "storageTarget": STORAGE_TARGET},
            {"itemKey": "item_B", "wordCleanTtsId": 3, "wordCleanId": 14346, "storageTarget": STORAGE_TARGET},
        ],
    }
    return worker.TaskRunSnapshot(
        id=31,
        task_name="单个单词TTS语音生成 - 批次 1",
        ai_prompt_json=json.dumps(prompt),
        ai_response_json="",
        record_type=record_type,
        requested_worker_count=2,
        handler_key=worker.TASK_CONFIG_4_HANDLER,
        executor_type=worker.TASK_CONFIG_4_EXECUTOR_TYPE,
        executor_id=worker.TASK_CONFIG_4_EXECUTOR_ID,
        task_config_id=4,
    )


def generated_tts(file_name="word_clean_14153_tts_2.wav"):
    return {
        "provider": worker.TASK_CONFIG_4_EXECUTOR_ID,
        "model": "mimo-v2.5-tts",
        "voice": "Chloe",
        "format": "wav",
        "fileName": file_name,
        "downloadUrl": f"http://127.0.0.1:19186/api/tts/files/{file_name}",
        "byteSize": 128,
        "contentType": "audio/wav",
    }


def generated_audio(file_name="word_clean_14153_tts_2.wav"):
    return worker.GeneratedTtsAudio(
        provider="xiaomi-mimo-tts",
        model="mimo-v2.5-tts",
        voice="Chloe",
        audio_format="wav",
        file_name=file_name,
        audio_bytes=b"RIFF\x10\x00\x00\x00WAVEfmt ",
    )


def stored_object(file_name="word_clean_14153_tts_2.wav"):
    return worker.StoredObject(
        bucket="ai-file-navigation",
        object_key=f"word_clean_tts/{file_name}",
        object_url=f"/ai-file-navigation/word_clean_tts/{file_name}",
        byte_size=20,
        md5="abc",
        etag="abc",
        reused=False,
    )


def test_batch_builder_reuses_result_payload_and_strict_target_snapshot():
    results = [
        result_snapshot(),
        result_snapshot(42, 3, 14346, "passive"),
    ]
    with patch.object(worker, "load_task_result_snapshots", return_value=results):
        prompt = worker.build_task_config_4_tts_batch_prompt(
            4,
            "单个单词TTS语音生成 - 批次 1",
            [41, 42],
        )

    assert prompt["taskType"] == "word_clean_tts_batch"
    assert prompt["batch"]["executionTarget"] == {
        "executorType": "AI_PROVIDER",
        "executorId": "xiaomi-mimo-tts",
    }
    assert prompt["batch"]["storageTarget"] == STORAGE_TARGET
    assert prompt["items"][0]["storageTarget"] == STORAGE_TARGET
    assert prompt["rules"]["continueOnItemFailure"] is True
    assert [item["itemKey"] for item in prompt["items"]] == ["item_A", "item_B"]
    first_payload = json.loads(results[0].result_content)
    assert prompt["items"][0]["ttsInput"] == first_payload["ttsInput"]
    assert prompt["items"][0]["writeBack"] == first_payload["writeBack"]
    assert prompt["items"][0]["wordCleanTtsId"] == 2


def test_batch_parser_rejects_duplicate_item_keys():
    task_run = run_snapshot()
    prompt = json.loads(task_run.ai_prompt_json)
    prompt["items"][1]["itemKey"] = "item_A"
    task_run.ai_prompt_json = json.dumps(prompt)

    with pytest.raises(worker.HTTPException, match="itemKey 重复"):
        worker.parse_task_config_4_tts_batch_prompt(task_run)


def test_batch_execution_rejects_real_run_from_another_task_config():
    task_run = run_snapshot()
    task_run.task_config_id = 5

    with pytest.raises(worker.HTTPException, match="任务配置快照必须为 4"):
        worker.validate_task_config_4_run_execution_snapshot(task_run)


def test_storage_context_rejects_result_run_or_live_config_mismatch():
    task_result = result_snapshot()
    task_run = run_snapshot()
    live_config = storage_config()

    with patch.object(worker, "load_object_storage_config_snapshot", return_value=live_config):
        target, loaded = worker.resolve_task_config_4_storage_context(task_result, task_run)
    assert target.as_payload() == STORAGE_TARGET
    assert loaded.id == 7

    prompt = json.loads(task_run.ai_prompt_json)
    prompt["batch"]["storageTarget"]["bucket"] = "other"
    task_run.ai_prompt_json = json.dumps(prompt)
    with (
        patch.object(worker, "load_object_storage_config_snapshot", return_value=live_config),
        pytest.raises(worker.HTTPException, match="对象存储快照不一致"),
    ):
        worker.resolve_task_config_4_storage_context(task_result, task_run)


def test_validation_item_calls_mimo_but_never_writes_source_table():
    task_result = result_snapshot()
    task_run = run_snapshot(worker.RECORD_TYPE_VALIDATION_CURRENT)
    with (
        patch.object(worker, "generate_mimo_tts_audio", return_value=generated_audio()) as generate,
        patch.object(worker, "build_minio_client", return_value=MagicMock()),
        patch.object(worker, "store_verified_wav", return_value=stored_object()),
        patch.object(worker, "load_connection_config_snapshot") as load_connection,
        patch.object(worker, "backfill_task_config_4_word_tts") as backfill,
        patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
    ):
        row, response = worker.process_task_config_4_tts_batch_item(
            task_result,
            task_run,
            "item_A",
            validation_execution=True,
        )

    assert row[1] == "SUCCESS"
    assert response["backfillResult"]["skipped"] is True
    generate.assert_called_once_with(
        json.loads(task_result.result_content)["ttsInput"],
        worker.TASK_CONFIG_4_EXECUTOR_ID,
        strict_provider=True,
    )
    load_connection.assert_not_called()
    backfill.assert_not_called()


def test_formal_item_backfills_its_own_source_row():
    task_result = result_snapshot()
    task_run = run_snapshot()
    with (
        patch.object(worker, "generate_mimo_tts_audio", return_value=generated_audio()),
        patch.object(worker, "build_minio_client", return_value=MagicMock()),
        patch.object(worker, "store_verified_wav", return_value=stored_object()),
        patch.object(worker, "load_connection_config_snapshot", return_value=MagicMock()),
        patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
        patch.object(
            worker,
            "backfill_task_config_4_word_tts",
            return_value={"wordCleanTtsId": 2, "sourceGuardMatched": True},
        ) as backfill,
    ):
        row, response = worker.process_task_config_4_tts_batch_item(
            task_result,
            task_run,
            "item_A",
        )

    assert row[1] == "SUCCESS"
    assert response["status"] == "SUCCESS"
    backfill.assert_called_once()
    assert backfill.call_args.args[1]["wordCleanTtsId"] == 2


def test_success_payload_removes_stale_failure_and_execution_fields():
    task_result = result_snapshot()
    payload = json.loads(task_result.result_content)
    payload.update({
        "processorError": "previous 429",
        "processedAt": "old-time",
        "mode": "old-mode",
        "taskRunId": 12,
        "itemKey": "item_Z",
        "failureStage": "MIMO_TTS",
        "ttsResult": {"objectKey": "old"},
        "execution": {"taskRunId": 12},
        "backfillResult": {"sourceGuardMatched": False},
    })
    task_result.result_content = json.dumps(payload)
    with (
        patch.object(worker, "generate_mimo_tts_audio", return_value=generated_audio()),
        patch.object(worker, "build_minio_client", return_value=MagicMock()),
        patch.object(worker, "store_verified_wav", return_value=stored_object()),
        patch.object(worker, "load_connection_config_snapshot", return_value=MagicMock()),
        patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
        patch.object(
            worker,
            "backfill_task_config_4_word_tts",
            return_value={"wordCleanTtsId": 2, "sourceGuardMatched": True},
        ),
    ):
        row, _ = worker.process_task_config_4_tts_batch_item(task_result, run_snapshot(), "item_A")

    completed_payload = row[3]
    for stale_key in (
        "processorError",
        "processedAt",
        "mode",
        "taskRunId",
        "itemKey",
        "failureStage",
    ):
        assert stale_key not in completed_payload
    assert completed_payload["ttsResult"]["objectKey"].startswith("word_clean_tts/")
    assert completed_payload["execution"]["taskRunId"] == 31
    assert completed_payload["backfillResult"]["sourceGuardMatched"] is True


def test_formal_item_only_succeeds_after_verified_upload_and_backfill():
    task_result = result_snapshot()
    task_run = run_snapshot()
    events = []

    def generate(*_args, **_kwargs):
        events.append("mimo")
        return generated_audio()

    def store(*_args, **_kwargs):
        events.append("minio")
        return stored_object()

    def backfill(*_args, **_kwargs):
        events.append("backfill")
        return {"wordCleanTtsId": 2, "sourceGuardMatched": True}

    with (
        patch.object(worker, "generate_mimo_tts_audio", side_effect=generate),
        patch.object(worker, "build_minio_client", return_value=MagicMock()),
        patch.object(worker, "store_verified_wav", side_effect=store),
        patch.object(worker, "load_connection_config_snapshot", return_value=MagicMock()),
        patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
        patch.object(worker, "backfill_task_config_4_word_tts", side_effect=backfill),
    ):
        row, response = worker.process_task_config_4_tts_batch_item(task_result, task_run, "item_A")

    assert row[1] == "SUCCESS"
    assert events == ["mimo", "minio", "backfill"]
    assert response["ttsResult"]["bucket"] == "ai-file-navigation"
    assert response["ttsResult"]["objectKey"].startswith("word_clean_tts/")
    assert response["ttsResult"]["storageVerified"] is True


def test_minio_failure_keeps_result_failed_and_skips_backfill():
    with (
        patch.object(worker, "generate_mimo_tts_audio", return_value=generated_audio()),
        patch.object(worker, "build_minio_client", return_value=MagicMock()),
        patch.object(worker, "store_verified_wav", side_effect=RuntimeError("minio unavailable")),
        patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
        patch.object(worker, "backfill_task_config_4_word_tts") as backfill,
    ):
        row, response = worker.process_task_config_4_tts_batch_item(result_snapshot(), run_snapshot(), "item_A")

    assert row[1] == "FAILED"
    assert row[3]["failureStage"] == "MINIO_UPLOAD"
    assert response["failureStage"] == "MINIO_UPLOAD"
    backfill.assert_not_called()


def test_backfill_failure_keeps_result_failed_after_verified_upload():
    with (
        patch.object(worker, "generate_mimo_tts_audio", return_value=generated_audio()),
        patch.object(worker, "build_minio_client", return_value=MagicMock()),
        patch.object(worker, "store_verified_wav", return_value=stored_object()),
        patch.object(worker, "load_connection_config_snapshot", return_value=MagicMock()),
        patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
        patch.object(worker, "backfill_task_config_4_word_tts", side_effect=RuntimeError("source changed")),
    ):
        row, response = worker.process_task_config_4_tts_batch_item(result_snapshot(), run_snapshot(), "item_A")

    assert row[1] == "FAILED"
    assert row[3]["failureStage"] == "SOURCE_BACKFILL"
    assert response["failureStage"] == "SOURCE_BACKFILL"


def test_item_failure_is_returned_without_raising_or_hiding_other_items():
    with (
        patch.object(worker, "generate_mimo_tts_audio", side_effect=RuntimeError("provider timeout")),
        patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
    ):
        row, response = worker.process_task_config_4_tts_batch_item(
            result_snapshot(),
            run_snapshot(),
            "item_A",
        )

    assert row[1] == "FAILED"
    assert "provider timeout" in row[4]
    assert response == {
        "itemKey": "item_A",
        "taskResultId": 41,
        "status": "FAILED",
        "errorMessage": "provider timeout",
        "failureStage": "MIMO_TTS",
    }


def test_formal_backfill_updates_real_schema_with_pending_source_guard():
    task_result = result_snapshot()
    payload = json.loads(task_result.result_content)
    cursor = MagicMock()
    cursor.rowcount = 1
    cursor_context = MagicMock()
    cursor_context.__enter__.return_value = cursor
    connection = MagicMock()
    connection.cursor.return_value = cursor_context
    connection_context = MagicMock()
    connection_context.__enter__.return_value = connection

    with patch.object(worker, "connect_source_database", return_value=connection_context):
        response = worker.backfill_task_config_4_word_tts(
            MagicMock(),
            payload,
            generated_tts(),
        )

    query = " ".join(cursor.execute.call_args.args[0].lower().split())
    assert "update public.word_clean_tts" in query
    assert "status = 'success'" in query
    assert "and status = 'pending'" in query
    assert "provider = %s" in query
    assert "tts_object_url = %s" in query
    assert response["wordCleanTtsId"] == 2
    assert response["sourceGuardMatched"] is True
    connection.commit.assert_called_once()


def test_batch_execution_keeps_item_order_and_isolates_failures():
    task_run = run_snapshot()
    results = [result_snapshot(), result_snapshot(42, 3, 14346, "passive")]

    def process_item(task_result, _task_run, item_key, validation_execution=False):
        assert validation_execution is False
        if task_result.id == 41:
            return (
                (41, "SUCCESS", "ok", {"itemKey": item_key}, ""),
                {"itemKey": item_key, "status": "SUCCESS"},
            )
        return (
            (42, "FAILED", "failed", {"itemKey": item_key}, "isolated"),
            {"itemKey": item_key, "status": "FAILED", "errorMessage": "isolated"},
        )

    with (
        patch.object(worker, "load_task_run_snapshot", return_value=task_run),
        patch.object(worker, "load_task_run_result_ids", return_value=[41, 42]),
        patch.object(worker, "load_task_result_snapshots", return_value=results),
        patch.object(worker, "batch_update_task_result_states") as update_results,
        patch.object(worker, "process_task_config_4_tts_batch_item", side_effect=process_item),
        patch.object(worker, "update_task_run_ai_response", return_value='{"ok":true}') as update_run,
    ):
        response = worker.process_task_config_4_tts_task_run_batch(31)

    assert response["successCount"] == 1
    assert response["failedCount"] == 1
    assert [row[0] for row in update_results.call_args_list[-1].args[0]] == [41, 42]
    run_response = update_run.call_args.args[1]
    assert [item["itemKey"] for item in run_response["items"]] == ["item_A", "item_B"]
    assert run_response["execution"]["continueOnItemFailure"] is True
    assert run_response["execution"]["workerCount"] == 1


def test_task_config_4_mimo_calls_are_globally_serialized():
    state_lock = threading.Lock()
    active = 0
    maximum = 0

    def generate(*_args, **_kwargs):
        nonlocal active, maximum
        with state_lock:
            active += 1
            maximum = max(maximum, active)
        time.sleep(0.02)
        with state_lock:
            active -= 1
        return generated_audio()

    with patch.object(worker, "generate_mimo_tts_audio", side_effect=generate):
        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [
                executor.submit(worker.generate_task_config_4_mimo_audio, {"text": "word"})
                for _ in range(2)
            ]
            for future in futures:
                future.result()

    assert maximum == 1


def test_validation_batch_updates_links_instead_of_formal_results():
    task_run = run_snapshot(worker.RECORD_TYPE_VALIDATION_CURRENT)
    results = [result_snapshot(), result_snapshot(42, 3, 14346, "passive")]

    def process_item(task_result, _task_run, item_key, validation_execution=False):
        assert validation_execution is True
        return (
            (task_result.id, "SUCCESS", "ok", None, ""),
            {"itemKey": item_key, "status": "SUCCESS"},
        )

    with (
        patch.object(worker, "load_task_run_snapshot", return_value=task_run),
        patch.object(worker, "load_task_run_result_ids", return_value=[41, 42]),
        patch.object(worker, "load_task_result_snapshots", return_value=results),
        patch.object(worker, "batch_update_task_result_states") as update_results,
        patch.object(worker, "batch_update_task_run_result_link_states") as update_links,
        patch.object(worker, "process_task_config_4_tts_batch_item", side_effect=process_item),
        patch.object(worker, "update_task_run_ai_response", return_value='{"ok":true}'),
    ):
        response = worker.process_task_config_4_tts_task_run_batch(31)

    assert response["validationExecution"] is True
    update_results.assert_not_called()
    assert update_links.call_args_list[0].args[1][0][1] == "RUNNING"
    assert update_links.call_args_list[-1].args[1][0][1] == "SUCCESS"
