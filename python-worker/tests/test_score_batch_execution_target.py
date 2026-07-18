import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_score_batch_target", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(worker)


class ScoreBatchExecutionTargetTest(unittest.TestCase):
    def test_validation_batch_uses_ai_provider_without_cli(self):
        prompt = {
            "taskType": worker.RESULT_MODE_SCORE_BATCH,
            "items": [{"itemKey": "item_A"}],
        }
        task_run = worker.TaskRunSnapshot(
            id=31,
            task_name="评分验证批次",
            ai_prompt_json=json.dumps(prompt),
            ai_response_json="",
            record_type=worker.RECORD_TYPE_VALIDATION_CURRENT,
            requested_worker_count=1,
            handler_key=worker.HANDLER_SCORE,
            executor_type="AI_PROVIDER",
            executor_id="openai-main",
        )
        result_payload = {
            "taskType": worker.RESULT_MODE_SCORE,
            "aiPrompt": "score this",
            "writeBack": {
                "wordCleanId": 7,
                "word": "example",
                "candidateMap": {
                    "A": {
                        "candidateSentenceId": 101,
                        "wordCleanId": 7,
                        "word": "example",
                        "modelName": "source-model",
                        "sentence": "This is an example.",
                        "sentenceTranslation": "这是一个例子。",
                    }
                },
            },
        }
        task_result = worker.TaskResultSnapshot(
            id=41,
            result_name="评分 41",
            task_config_id=2,
            project_id=1,
            database_config_id=1,
            status="PENDING",
            record_type=worker.RECORD_TYPE_FORMAL,
            result_content=json.dumps(result_payload),
            handler_key=worker.HANDLER_SCORE,
            executor_type="AI_PROVIDER",
            executor_id="openai-main",
        )
        target = worker.ExecutionTarget(
            executor_type="AI_PROVIDER",
            executor_id="openai-main",
            label="OpenAI Main",
            protocol="openai-compatible",
            capabilities=("TEXT_GENERATION",),
            config={"model": "provider-model"},
        )
        execution_result = {
            "rawOutput": json.dumps(
                {
                    "items": [
                        {
                            "itemKey": "item_A",
                            "scores": [{"candidate": "A", "score": 95, "reason": "good"}],
                            "bestCandidate": "A",
                        }
                    ]
                }
            ),
            "executorType": "AI_PROVIDER",
            "executorId": "openai-main",
            "executorLabel": "OpenAI Main",
            "protocol": "openai-compatible",
            "model": "provider-model",
            "metadata": {},
        }

        with (
            patch.object(worker, "load_task_run_snapshot", return_value=task_run),
            patch.object(worker, "load_task_run_result_ids", return_value=[41]),
            patch.object(worker, "load_task_result_snapshots", return_value=[task_result]),
            patch.object(worker, "resolve_execution_target", return_value=target) as resolve_target,
            patch.object(worker, "execute_text_generation", return_value=execution_result) as execute_text,
            patch.object(worker, "find_cli_config", side_effect=AssertionError("不应读取 CLI")),
            patch.object(worker, "run_cli_prompt", side_effect=AssertionError("不应调用 CLI")),
            patch.object(worker, "batch_update_task_run_result_link_states") as update_links,
            patch.object(worker, "batch_update_task_result_states") as update_results,
            patch.object(worker, "update_task_run_ai_response", return_value='{"ok":true}'),
        ):
            response = worker.process_word_clean_sentence_task_run_batch(31)

        self.assertEqual(1, response["successCount"])
        self.assertEqual(0, response["failedCount"])
        self.assertEqual("AI_PROVIDER", response["executorType"])
        self.assertEqual("openai-main", response["executorId"])
        resolve_target.assert_called_once_with("AI_PROVIDER", "openai-main", "TEXT_GENERATION")
        execute_text.assert_called_once_with(target, task_run.ai_prompt_json)
        update_results.assert_not_called()
        self.assertEqual("SUCCESS", update_links.call_args_list[-1].args[1][0][1])


if __name__ == "__main__":
    unittest.main()
