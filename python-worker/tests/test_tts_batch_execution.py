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


def run_snapshot(record_type=worker.RECORD_TYPE_FORMAL):
    prompt = {
        "taskType": worker.RESULT_MODE_TTS_BATCH,
        "version": 1,
        "items": [
            {"itemKey": "item_A", "taskType": worker.RESULT_MODE_TTS},
            {"itemKey": "item_B", "taskType": worker.RESULT_MODE_TTS},
        ],
    }
    return worker.TaskRunSnapshot(
        id=31,
        task_name="生成 TTS 任务 - 批次 1",
        cli_id="codex",
        ai_prompt_json=json.dumps(prompt),
        ai_response_json="",
        record_type=record_type,
        requested_worker_count=2,
    )


def result_snapshot(result_id, best_sentence_id, word_clean_id):
    payload = {
        "taskType": worker.RESULT_MODE_TTS,
        "bestSentenceId": best_sentence_id,
        "wordCleanId": word_clean_id,
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
        cli_id="codex",
        database_config_id=1,
        status="PENDING",
        record_type=worker.RECORD_TYPE_FORMAL,
        result_content=json.dumps(payload),
    )


class TtsBatchExecutionTest(unittest.TestCase):
    def test_parses_tts_batch_execution_payload(self):
        prompt = worker.parse_tts_task_run_batch_prompt(run_snapshot())

        self.assertEqual(worker.RESULT_MODE_TTS_BATCH, prompt["taskType"])
        self.assertEqual(["item_A", "item_B"], [item["itemKey"] for item in prompt["items"]])

    def test_dispatches_tts_batch_without_score_cli_processor(self):
        task_run = run_snapshot()
        with (
            patch.object(worker, "load_task_run_snapshot", return_value=task_run),
            patch.object(
                worker,
                "process_word_clean_best_sentence_tts_task_run_batch",
                return_value={"mode": "task-run-tts-batch"},
            ) as process_tts,
            patch.object(worker, "process_word_clean_sentence_task_run_batch") as process_score,
        ):
            response = worker.process_task_run_batch_by_type(31, "codex")

        self.assertEqual("task-run-tts-batch", response["mode"])
        process_tts.assert_called_once_with(31, "codex")
        process_score.assert_not_called()

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
            response = worker.process_word_clean_best_sentence_tts_task_run_batch(31, "codex")

        self.assertEqual(2, response["successCount"])
        self.assertEqual(0, response["failedCount"])
        self.assertEqual(2, process_item_mock.call_count)
        self.assertEqual("RUNNING", update_states.call_args_list[0].args[0][0][1])
        self.assertEqual("SUCCESS", update_states.call_args_list[1].args[0][0][1])
        run_response = update_run.call_args.args[1]
        self.assertEqual(worker.RESULT_MODE_TTS_BATCH, run_response["taskType"])
        self.assertFalse(run_response["execution"]["legacyJobTableDependency"])
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
            response = worker.process_word_clean_best_sentence_tts_task_run_batch(31, "codex")

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
        tts_result = {
            "fileName": "best-101.wav",
            "downloadUrl": "http://127.0.0.1:19186/api/tts/files/best-101.wav",
        }

        with (
            patch.object(worker, "generate_mimo_tts", return_value=tts_result),
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
        load_connection.assert_not_called()
        backfill.assert_not_called()

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
