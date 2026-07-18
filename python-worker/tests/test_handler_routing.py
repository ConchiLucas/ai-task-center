import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_handler_routing", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(worker)


def run_snapshot(handler_key="", executor_type="", executor_id="", payload_task_type=None):
    return worker.TaskRunSnapshot(
        id=31,
        task_name="测试批次",
        cli_id="codex",
        ai_prompt_json=json.dumps({"taskType": payload_task_type or worker.RESULT_MODE_SCORE_BATCH}),
        ai_response_json="",
        record_type=worker.RECORD_TYPE_FORMAL,
        requested_worker_count=1,
        handler_key=handler_key,
        executor_type=executor_type,
        executor_id=executor_id,
    )


def result_snapshot():
    return worker.TaskResultSnapshot(
        id=41,
        result_name="TTS 41",
        task_config_id=1,
        project_id=1,
        cli_id="codex",
        database_config_id=1,
        status="PENDING",
        record_type=worker.RECORD_TYPE_FORMAL,
        result_content=json.dumps(
            {
                "taskType": worker.RESULT_MODE_TTS,
                "bestSentenceId": 101,
                "wordCleanId": 7,
                "source": {"sourceSentenceId": 501, "sentence": "Example."},
                "ttsInput": {"text": "Example.", "fileName": "best-101.wav"},
                "writeBack": {"table": worker.WORD_CLEAN_BEST_SENTENCE_TABLE},
            }
        ),
        handler_key="word_clean_best_sentence_tts",
        executor_type="AI_PROVIDER",
        executor_id="custom-mimo",
    )


class HandlerRoutingTest(unittest.TestCase):
    def test_run_without_handler_never_routes_by_payload_type(self):
        task_run = run_snapshot(payload_task_type=worker.RESULT_MODE_TTS_BATCH)
        with patch.object(worker, "load_task_run_snapshot", return_value=task_run):
            with self.assertRaisesRegex(worker.HTTPException, "任务处理器未注册"):
                worker.process_task_run_batch_by_type(31, "codex")

    def test_tts_item_uses_snapshotted_provider_id(self):
        task_result = result_snapshot()
        task_run = run_snapshot(
            handler_key="word_clean_best_sentence_tts",
            executor_type="AI_PROVIDER",
            executor_id="custom-mimo",
            payload_task_type=worker.RESULT_MODE_TTS_BATCH,
        )
        tts_result = {
            "fileName": "best-101.wav",
            "downloadUrl": "http://127.0.0.1:19186/api/tts/files/best-101.wav",
        }

        with patch.object(worker, "generate_mimo_tts", return_value=tts_result) as generate_tts:
            row, _ = worker.process_tts_batch_item(
                task_result,
                task_run,
                "item_A",
                validation_execution=True,
            )

        self.assertEqual("SUCCESS", row[1])
        generate_tts.assert_called_once_with(
            json.loads(task_result.result_content)["ttsInput"],
            "custom-mimo",
            strict_provider=True,
        )


if __name__ == "__main__":
    unittest.main()
