import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(worker)


class ResultRecordTypeTest(unittest.TestCase):
    def test_generated_row_keeps_requested_record_type(self):
        task = worker.TaskConfigSnapshot(
            id=1,
            task_name="测试任务",
            project_id=2,
            cli_id="codex",
            database_config_id=3,
            selected_tables='["public.word_clean_sentence"]',
        )
        groups = [{
            "wordCleanId": 10,
            "word": "example",
            "candidateCount": 1,
            "candidates": [{
                "id": 100,
                "modelName": "model",
                "sentence": "An example sentence.",
                "sentenceTranslation": "一个示例句子。",
                "hasScore": False,
            }],
        }]

        rows = worker.build_score_result_rows(
            task,
            ["public.word_clean_sentence"],
            "scripts/generated.py",
            groups,
            set(),
            worker.RECORD_TYPE_VALIDATION_CURRENT,
        )

        self.assertEqual(1, len(rows))
        self.assertEqual(worker.RECORD_TYPE_VALIDATION_CURRENT, rows[0][-1])


if __name__ == "__main__":
    unittest.main()
