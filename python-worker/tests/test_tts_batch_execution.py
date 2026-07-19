import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_tts_batch", MODULE_PATH)
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
        bucket_name="ai-file-navigation",
        base_path="word_clean_tts",
        enabled=True,
        is_default=True,
    )


def run_snapshot(record_type=worker.RECORD_TYPE_FORMAL):
    prompt = {
        "taskType": worker.RESULT_MODE_TTS_BATCH,
        "version": 1,
        "batch": {
            "taskConfigId": 1,
            "storageTarget": STORAGE_TARGET,
        },
        "items": [
            {
                "itemKey": "item_A",
                "taskType": worker.RESULT_MODE_TTS,
                "bestSentenceId": 101,
                "wordCleanId": 7,
                "storageTarget": STORAGE_TARGET,
            },
            {
                "itemKey": "item_B",
                "taskType": worker.RESULT_MODE_TTS,
                "bestSentenceId": 102,
                "wordCleanId": 8,
                "storageTarget": STORAGE_TARGET,
            },
        ],
    }
    return worker.TaskRunSnapshot(
        id=31,
        task_name="生成 TTS 任务 - 批次 1",
        ai_prompt_json=json.dumps(prompt),
        ai_response_json="",
        record_type=record_type,
        requested_worker_count=2,
        handler_key=worker.HANDLER_TTS,
        executor_type="AI_PROVIDER",
        executor_id="xiaomi-mimo-tts",
        task_config_id=1,
    )


def result_snapshot(result_id, best_sentence_id, word_clean_id):
    payload = {
        "taskType": worker.RESULT_MODE_TTS,
        "bestSentenceId": best_sentence_id,
        "wordCleanId": word_clean_id,
        "storageTarget": STORAGE_TARGET,
        "source": {
            "sourceSentenceId": 501 + result_id,
            "sentence": f"Sentence {result_id}.",
        },
        "ttsInput": {
            "text": f"Sentence {result_id}.",
            "fileName": f"best-{best_sentence_id}.wav",
            "defaultAudioFormat": "wav",
        },
        "writeBack": {
            "table": worker.WORD_CLEAN_BEST_SENTENCE_TABLE,
            "bestSentenceId": best_sentence_id,
        },
    }
    return worker.TaskResultSnapshot(
        id=result_id,
        result_name=f"TTS {result_id}",
        task_config_id=1,
        project_id=1,
        database_config_id=1,
        status="PENDING",
        record_type=worker.RECORD_TYPE_FORMAL,
        result_content=json.dumps(payload),
    )


def generated_audio(file_name="best-101.wav"):
    return worker.GeneratedTtsAudio(
        provider="xiaomi-mimo-tts",
        model="mimo-v2.5-tts",
        voice="Chloe",
        audio_format="wav",
        file_name=file_name,
        audio_bytes=b"RIFF\x10\x00\x00\x00WAVEfmt ",
    )


def stored_object(file_name="best-101.wav"):
    return worker.StoredObject(
        bucket="ai-file-navigation",
        object_key=f"word_clean_tts/{file_name}",
        object_url=f"/ai-file-navigation/word_clean_tts/{file_name}",
        byte_size=20,
        md5="abc",
        etag="abc",
        reused=False,
    )


class TtsBatchExecutionTest(unittest.TestCase):
    def test_batch_builder_reuses_strict_storage_target(self):
        results = [result_snapshot(41, 101, 7), result_snapshot(42, 102, 8)]
        with patch.object(worker, "load_task_result_snapshots", return_value=results):
            prompt = worker.build_tts_handler_batch_prompt(1, "生成 TTS 任务 - 批次 1", [41, 42])

        self.assertEqual(STORAGE_TARGET, prompt["batch"]["storageTarget"])
        self.assertEqual(STORAGE_TARGET, prompt["items"][0]["storageTarget"])
        self.assertEqual(101, prompt["items"][0]["bestSentenceId"])

    def test_storage_context_rejects_result_run_or_live_config_mismatch(self):
        task_result = result_snapshot(41, 101, 7)
        task_run = run_snapshot()

        with patch.object(
            worker,
            "load_object_storage_config_snapshot",
            return_value=storage_config(),
        ):
            target, loaded = worker.resolve_tts_storage_context(task_result, task_run)

        self.assertEqual(STORAGE_TARGET, target.as_payload())
        self.assertEqual(7, loaded.id)

        prompt = json.loads(task_run.ai_prompt_json)
        prompt["batch"]["storageTarget"]["bucket"] = "other"
        task_run.ai_prompt_json = json.dumps(prompt)
        with (
            patch.object(
                worker,
                "load_object_storage_config_snapshot",
                return_value=storage_config(),
            ),
            self.assertRaisesRegex(worker.HTTPException, "对象存储快照不一致"),
        ):
            worker.resolve_tts_storage_context(task_result, task_run)

    def test_parses_tts_batch_execution_payload(self):
        prompt = worker.parse_tts_task_run_batch_prompt(run_snapshot())

        self.assertEqual(worker.RESULT_MODE_TTS_BATCH, prompt["taskType"])
        self.assertEqual(["item_A", "item_B"], [item["itemKey"] for item in prompt["items"]])

    def test_dispatches_tts_batch_without_score_cli_processor(self):
        task_run = run_snapshot()
        process_tts = MagicMock(return_value={"mode": "task-run-tts-batch"})
        registry = worker.TaskHandlerRegistry()
        registry.register(worker.TaskHandlerDefinition(
            worker.HANDLER_TTS,
            "AUDIO_TTS",
            batch_processor=process_tts,
        ))
        with (
            patch.object(worker, "load_task_run_snapshot", return_value=task_run),
            patch.object(worker, "TASK_HANDLER_REGISTRY", registry),
        ):
            response = worker.process_task_run_batch_by_type(31)

        self.assertEqual("task-run-tts-batch", response["mode"])
        process_tts.assert_called_once_with(31)

    def test_tts_batch_updates_each_result_and_batch_response(self):
        task_run = run_snapshot()
        task_results = [result_snapshot(41, 101, 7), result_snapshot(42, 102, 8)]

        def process_item(task_result, _task_run, item_key, validation_execution=False):
            self.assertFalse(validation_execution)
            row = (
                task_result.id,
                "SUCCESS",
                "TTS 生成并回填成功",
                {"taskType": worker.RESULT_MODE_TTS, "itemKey": item_key},
                "",
            )
            return row, {"itemKey": item_key, "status": "SUCCESS"}

        with (
            patch.object(worker, "load_task_run_snapshot", return_value=task_run),
            patch.object(worker, "load_task_run_result_ids", return_value=[41, 42]),
            patch.object(worker, "load_task_result_snapshots", return_value=task_results),
            patch.object(worker, "batch_update_task_result_states") as update_states,
            patch.object(worker, "process_tts_batch_item", side_effect=process_item) as process_item_mock,
            patch.object(worker, "update_task_run_ai_response", return_value='{"ok":true}') as update_run,
        ):
            response = worker.process_word_clean_best_sentence_tts_task_run_batch(31)

        self.assertEqual(2, response["successCount"])
        self.assertEqual(0, response["failedCount"])
        self.assertEqual(2, process_item_mock.call_count)
        self.assertEqual("RUNNING", update_states.call_args_list[0].args[0][0][1])
        self.assertEqual("SUCCESS", update_states.call_args_list[1].args[0][0][1])
        run_response = update_run.call_args.args[1]
        self.assertEqual(worker.RESULT_MODE_TTS_BATCH, run_response["taskType"])
        self.assertEqual("python-worker-mimo-tts", run_response["execution"]["service"])
        self.assertEqual(1, run_response["execution"]["workerCount"])
        self.assertFalse(run_response["execution"]["validationExecution"])

    def test_current_validation_batch_executes_without_updating_formal_results(self):
        task_run = run_snapshot(worker.RECORD_TYPE_VALIDATION_CURRENT)
        task_results = [result_snapshot(41, 101, 7), result_snapshot(42, 102, 8)]

        def process_item(task_result, _task_run, item_key, validation_execution=False):
            self.assertTrue(validation_execution)
            row = (task_result.id, "SUCCESS", "TTS 验证生成成功", None, "")
            response = {
                "itemKey": item_key,
                "status": "SUCCESS",
                "ttsResult": {
                    "downloadUrl": f"http://127.0.0.1:19186/api/tts/files/{item_key}.wav"
                },
            }
            return row, response

        with (
            patch.object(worker, "load_task_run_snapshot", return_value=task_run),
            patch.object(worker, "load_task_run_result_ids", return_value=[41, 42]),
            patch.object(worker, "load_task_result_snapshots", return_value=task_results),
            patch.object(worker, "batch_update_task_result_states") as update_results,
            patch.object(worker, "batch_update_task_run_result_link_states") as update_links,
            patch.object(worker, "process_tts_batch_item", side_effect=process_item),
            patch.object(worker, "update_task_run_ai_response", return_value='{"ok":true}') as update_run,
        ):
            response = worker.process_word_clean_best_sentence_tts_task_run_batch(31)

        self.assertTrue(response["validationExecution"])
        self.assertEqual(2, response["successCount"])
        update_results.assert_not_called()
        self.assertEqual("RUNNING", update_links.call_args_list[0].args[1][0][1])
        self.assertEqual("SUCCESS", update_links.call_args_list[1].args[1][0][1])
        run_response = update_run.call_args.args[1]
        self.assertTrue(run_response["execution"]["validationExecution"])
        self.assertTrue(run_response["execution"]["sourceWriteBackSkipped"])

    def test_validation_tts_item_skips_source_backfill(self):
        task_result = result_snapshot(41, 101, 7)
        task_run = run_snapshot(worker.RECORD_TYPE_VALIDATION_CURRENT)
        with (
            patch.object(worker, "generate_mimo_tts_audio", return_value=generated_audio()) as generate,
            patch.object(worker, "build_minio_client", return_value=MagicMock()),
            patch.object(worker, "store_verified_wav", return_value=stored_object()),
            patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
            patch.object(worker, "load_connection_config_snapshot") as load_connection,
            patch.object(worker, "backfill_word_clean_best_sentence_tts") as backfill,
        ):
            row, response_item = worker.process_tts_batch_item(
                task_result,
                task_run,
                "item_A",
                validation_execution=True,
            )

        self.assertEqual("SUCCESS", row[1])
        self.assertTrue(response_item["validationExecution"])
        self.assertTrue(response_item["backfillResult"]["skipped"])
        self.assertTrue(response_item["ttsResult"]["storageVerified"])
        generate.assert_called_once_with(
            json.loads(task_result.result_content)["ttsInput"],
            "xiaomi-mimo-tts",
            strict_provider=True,
        )
        load_connection.assert_not_called()
        backfill.assert_not_called()

    def test_formal_item_only_succeeds_after_verified_upload_and_backfill(self):
        task_result = result_snapshot(41, 101, 7)
        events = []

        def generate(*_args, **_kwargs):
            events.append("mimo")
            return generated_audio()

        def store(*_args, **_kwargs):
            events.append("minio")
            return stored_object()

        def backfill(*_args, **_kwargs):
            events.append("backfill")
            return {"bestSentenceId": 101, "sourceGuardMatched": True}

        with (
            patch.object(worker, "generate_mimo_tts_audio", side_effect=generate),
            patch.object(worker, "build_minio_client", return_value=MagicMock()),
            patch.object(worker, "store_verified_wav", side_effect=store),
            patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
            patch.object(worker, "load_connection_config_snapshot", return_value=MagicMock()),
            patch.object(worker, "backfill_word_clean_best_sentence_tts", side_effect=backfill),
        ):
            row, response = worker.process_tts_batch_item(task_result, run_snapshot(), "item_A")

        self.assertEqual("SUCCESS", row[1])
        self.assertEqual(["mimo", "minio", "backfill"], events)
        self.assertTrue(response["ttsResult"]["storageVerified"])
        self.assertIn("/api/tts/storage/7/", response["ttsResult"]["downloadUrl"])

    def test_minio_failure_marks_stage_and_skips_source_backfill(self):
        with (
            patch.object(worker, "generate_mimo_tts_audio", return_value=generated_audio()),
            patch.object(worker, "build_minio_client", return_value=MagicMock()),
            patch.object(worker, "store_verified_wav", side_effect=RuntimeError("minio unavailable")),
            patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
            patch.object(worker, "backfill_word_clean_best_sentence_tts") as backfill,
        ):
            row, response = worker.process_tts_batch_item(
                result_snapshot(41, 101, 7),
                run_snapshot(),
                "item_A",
            )

        self.assertEqual("FAILED", row[1])
        self.assertEqual("MINIO_UPLOAD", row[3]["failureStage"])
        self.assertEqual("MINIO_UPLOAD", response["failureStage"])
        backfill.assert_not_called()

    def test_backfill_failure_stays_failed_after_verified_upload(self):
        with (
            patch.object(worker, "generate_mimo_tts_audio", return_value=generated_audio()),
            patch.object(worker, "build_minio_client", return_value=MagicMock()),
            patch.object(worker, "store_verified_wav", return_value=stored_object()),
            patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()),
            patch.object(worker, "load_connection_config_snapshot", return_value=MagicMock()),
            patch.object(
                worker,
                "backfill_word_clean_best_sentence_tts",
                side_effect=RuntimeError("source changed"),
            ),
        ):
            row, response = worker.process_tts_batch_item(
                result_snapshot(41, 101, 7),
                run_snapshot(),
                "item_A",
            )

        self.assertEqual("FAILED", row[1])
        self.assertEqual("SOURCE_BACKFILL", row[3]["failureStage"])
        self.assertEqual("SOURCE_BACKFILL", response["failureStage"])

    def test_formal_tts_backfill_updates_best_sentence_without_job_table(self):
        task_result = result_snapshot(41, 101, 7)
        payload = json.loads(task_result.result_content)
        cursor = MagicMock()
        cursor.rowcount = 1
        cursor_context = MagicMock()
        cursor_context.__enter__.return_value = cursor
        connection = MagicMock()
        connection.cursor.return_value = cursor_context
        connection_context = MagicMock()
        connection_context.__enter__.return_value = connection
        tts_result = {
            "fileName": "best-101.wav",
            "downloadUrl": "http://127.0.0.1:19186/api/tts/files/best-101.wav",
            "model": "mimo-v2.5-tts",
            "voice": "Chloe",
            "format": "wav",
            "byteSize": 128,
            "contentType": "audio/wav",
        }

        with patch.object(worker, "connect_source_database", return_value=connection_context):
            result = worker.backfill_word_clean_best_sentence_tts(MagicMock(), payload, tts_result)

        query = cursor.execute.call_args.args[0].lower()
        self.assertIn("update public.word_clean_best_sentence", query)
        self.assertNotIn("word_clean_sentence_tts_job", query)
        self.assertIn("source_sentence_id", query)
        self.assertEqual("xiaomi-mimo-tts", result["provider"])
        self.assertTrue(result["sourceGuardMatched"])
        self.assertEqual(101, result["bestSentenceId"])
        connection.commit.assert_called_once()


if __name__ == "__main__":
    unittest.main()
