import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_validation_execution", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(worker)


def snapshot(
    task_result_id: int = 7,
    task_type: str = worker.RESULT_MODE_TTS,
    record_type: str = worker.RECORD_TYPE_VALIDATION_CURRENT,
):
    if task_type == worker.RESULT_MODE_TTS:
        payload = {
            "taskType": worker.RESULT_MODE_TTS,
            "ttsInput": {
                "text": "This is an example.",
                "fileName": "word_clean_7_best_11.wav",
                "defaultAudioFormat": "wav",
            },
        }
    else:
        payload = {
            "taskType": worker.RESULT_MODE_SCORE,
            "aiPrompt": "score this sentence",
            "writeBack": {"candidateMap": {"A": {"id": 101}}},
        }
    return worker.TaskResultSnapshot(
        id=task_result_id,
        result_name="验证结果",
        task_config_id=1,
        project_id=2,
        database_config_id=3,
        status="PENDING",
        record_type=record_type,
        result_content=json.dumps(payload, ensure_ascii=False),
        handler_key=worker.HANDLER_TTS if task_type == worker.RESULT_MODE_TTS else worker.HANDLER_SCORE,
        executor_type="AI_PROVIDER" if task_type == worker.RESULT_MODE_TTS else "CLI",
        executor_id="xiaomi-mimo-tts" if task_type == worker.RESULT_MODE_TTS else "codex",
    )


class ValidationExecutionTest(unittest.TestCase):
    def test_tts_validation_executes_and_only_updates_task_result(self):
        task_result = snapshot()
        tts_response = {
            "fileName": "word_clean_7_best_11.wav",
            "downloadUrl": "http://127.0.0.1:19186/api/tts/files/word_clean_7_best_11.wav",
            "byteSize": 128,
        }
        with (
            patch.object(worker, "generate_mimo_tts", return_value=tts_response) as generate_tts,
            patch.object(worker, "update_task_result_state") as update_state,
        ):
            result = worker.process_tts_validation_task_result(task_result)

        self.assertEqual("SUCCESS", result["status"])
        generate_tts.assert_called_once()
        self.assertEqual("RUNNING", update_state.call_args_list[0].args[1])
        self.assertEqual("SUCCESS", update_state.call_args_list[1].args[1])
        completed_payload = update_state.call_args_list[1].args[3]
        self.assertTrue(completed_payload["validationExecution"]["sourceWriteBackSkipped"])
        self.assertEqual(tts_response, completed_payload["ttsResult"])

    def test_tts_history_cannot_execute(self):
        with self.assertRaises(worker.HTTPException) as raised:
            worker.process_tts_validation_task_result(
                snapshot(record_type=worker.RECORD_TYPE_VALIDATION_HISTORY)
            )

        self.assertEqual(400, raised.exception.status_code)

    def test_dispatches_tts_validation_by_registered_handler(self):
        task_result = snapshot()
        process_tts = MagicMock(return_value={"status": "SUCCESS"})
        registry = worker.TaskHandlerRegistry()
        registry.register(worker.TaskHandlerDefinition(
            worker.HANDLER_TTS,
            "AUDIO_TTS",
            single_processor=process_tts,
        ))
        with (
            patch.object(worker, "load_task_result_snapshot", return_value=task_result),
            patch.object(worker, "TASK_HANDLER_REGISTRY", registry),
        ):
            result = worker.process_task_result_by_type(7)

        self.assertEqual("SUCCESS", result["status"])
        process_tts.assert_called_once_with(7)

    def test_score_validation_skips_source_database_backfill(self):
        task_result = snapshot(task_type=worker.RESULT_MODE_SCORE)
        cli_config = {
            "id": "codex",
            "label": "Codex",
            "access": {"accessible": True},
        }
        cli_response = {
            "stdout": '{"scores":[{"candidate":"A","score":95,"reason":"good"}],"bestCandidate":"A"}',
            "command": "codex",
            "defaultArgs": [],
            "effectiveArgs": [],
            "model": "test-model",
            "reasoningEffort": "medium",
            "workingDirectory": "/tmp",
            "returnCode": 0,
            "stderr": "",
        }
        target = worker.ExecutionTarget(
            executor_type="CLI",
            executor_id="codex",
            label="Codex",
            protocol="local-cli",
            capabilities=("TEXT_GENERATION",),
            config=cli_config,
        )
        with (
            patch.object(worker, "load_task_result_snapshot", return_value=task_result),
            patch.object(worker, "resolve_execution_target", return_value=target) as resolve_target,
            patch.object(worker, "find_cli_config", return_value=cli_config),
            patch.object(worker, "run_cli_prompt", return_value=cli_response),
            patch.object(worker, "update_task_result_state") as update_state,
            patch.object(worker, "load_connection_config_snapshot") as load_connection,
            patch.object(worker, "backfill_word_clean_sentence_score") as backfill,
        ):
            result = worker.process_word_clean_sentence_task_result(7)

        self.assertEqual("SUCCESS", result["status"])
        resolve_target.assert_called_once_with("CLI", "codex", "TEXT_GENERATION")
        self.assertTrue(result["backfillResult"]["skipped"])
        load_connection.assert_not_called()
        backfill.assert_not_called()
        self.assertEqual("SUCCESS", update_state.call_args_list[-1].args[1])

    def test_score_validation_can_use_ai_provider_without_cli(self):
        task_result = snapshot(task_type=worker.RESULT_MODE_SCORE)
        task_result.executor_type = "AI_PROVIDER"
        task_result.executor_id = "openai-main"
        target = worker.ExecutionTarget(
            executor_type="AI_PROVIDER",
            executor_id="openai-main",
            label="OpenAI Main",
            protocol="openai-compatible",
            capabilities=("TEXT_GENERATION",),
            config={"model": "provider-model"},
        )
        execution_result = {
            "rawOutput": '{"scores":[{"candidate":"A","score":95,"reason":"good"}],"bestCandidate":"A"}',
            "executorType": "AI_PROVIDER",
            "executorId": "openai-main",
            "executorLabel": "OpenAI Main",
            "protocol": "openai-compatible",
            "model": "provider-model",
            "metadata": {},
        }
        with (
            patch.object(worker, "load_task_result_snapshot", return_value=task_result),
            patch.object(worker, "resolve_execution_target", return_value=target) as resolve_target,
            patch.object(worker, "execute_text_generation", return_value=execution_result) as execute_text,
            patch.object(worker, "find_cli_config", side_effect=AssertionError("不应读取 CLI")),
            patch.object(worker, "run_cli_prompt", side_effect=AssertionError("不应调用 CLI")),
            patch.object(worker, "update_task_result_state") as update_state,
            patch.object(worker, "load_connection_config_snapshot") as load_connection,
            patch.object(worker, "backfill_word_clean_sentence_score") as backfill,
        ):
            result = worker.process_word_clean_sentence_task_result(7)

        self.assertEqual("SUCCESS", result["status"])
        resolve_target.assert_called_once_with("AI_PROVIDER", "openai-main", "TEXT_GENERATION")
        execute_text.assert_called_once_with(target, "score this sentence")
        load_connection.assert_not_called()
        backfill.assert_not_called()
        completed_payload = update_state.call_args_list[-1].args[3]
        self.assertEqual("AI_PROVIDER", completed_payload["execution"]["executorType"])
        self.assertEqual("openai-main", completed_payload["execution"]["executorId"])

    def test_batch_executes_all_current_validation_results(self):
        task_results = [snapshot(7), snapshot(8)]
        with (
            patch.object(worker, "load_task_result_snapshots", return_value=task_results),
            patch.object(worker, "process_task_result_by_type", return_value={"status": "SUCCESS"}) as process_one,
        ):
            result = worker.process_validation_task_results_batch([7, 8], 2)

        self.assertEqual("validation-direct-batch", result["mode"])
        self.assertEqual(2, result["successCount"])
        self.assertEqual(0, result["failedCount"])
        self.assertEqual(2, process_one.call_count)
        process_one.assert_any_call(7)
        process_one.assert_any_call(8)


if __name__ == "__main__":
    unittest.main()
