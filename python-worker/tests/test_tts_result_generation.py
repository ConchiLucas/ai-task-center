import importlib.util
import json
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_tts", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(worker)


def task_config(selected_tables: str = '["public.word_clean_best_sentence"]'):
    return worker.TaskConfigSnapshot(
        id=1,
        task_name="生成 TTS 任务",
        project_id=2,
        database_config_id=3,
        selected_tables=selected_tables,
        handler_key=worker.HANDLER_TTS,
        executor_type="AI_PROVIDER",
        executor_id="xiaomi-mimo-tts",
    )


def best_sentence(best_sentence_id: int = 11, word_clean_id: int = 7):
    return {
        "bestSentenceId": best_sentence_id,
        "wordCleanId": word_clean_id,
        "word": "example",
        "meaning": "例子",
        "sourceSentenceId": 101,
        "sourceModelName": "sentence-model",
        "sentence": "This is an example.",
        "sentenceTranslation": "这是一个例子。",
        "score": 96,
        "scoreReason": "表达自然",
        "scoreModelName": "judge-model",
        "scoredAt": "2026-07-15T09:00:00+00:00",
    }


class TtsResultGenerationTest(unittest.TestCase):
    def test_detects_tts_generation_from_selected_table(self):
        mode = worker.detect_result_generation_mode(["WORD_CLEAN_BEST_SENTENCE"])

        self.assertEqual(worker.RESULT_MODE_TTS, mode)

    def test_rejects_ambiguous_supported_source_tables(self):
        with self.assertRaises(worker.HTTPException) as raised:
            worker.detect_result_generation_mode([
                "public.word_clean_sentence",
                "public.word_clean_best_sentence",
            ])

        self.assertEqual(400, raised.exception.status_code)

    def test_builds_pending_tts_result_with_source_and_write_back_contract(self):
        rows = worker.build_tts_result_rows(
            task_config(),
            ["public.word_clean_best_sentence"],
            [best_sentence()],
            set(),
            worker.RECORD_TYPE_VALIDATION_CURRENT,
        )

        self.assertEqual(1, len(rows))
        row = rows[0]
        payload = json.loads(row[12])
        self.assertEqual(worker.HANDLER_TTS, row[4])
        self.assertEqual("AI_PROVIDER", row[5])
        self.assertEqual("xiaomi-mimo-tts", row[6])
        self.assertEqual(worker.TTS_SOURCE_DESCRIPTION, row[9])
        self.assertEqual("PENDING", row[10])
        self.assertEqual(worker.RECORD_TYPE_VALIDATION_CURRENT, row[-1])
        self.assertEqual(worker.RESULT_MODE_TTS, payload["taskType"])
        self.assertEqual(11, payload["bestSentenceId"])
        self.assertEqual("This is an example.", payload["ttsInput"]["text"])
        self.assertEqual("word_clean_7_best_11.wav", payload["ttsInput"]["fileName"])
        self.assertEqual(11, payload["writeBack"]["match"]["id"])
        self.assertEqual(101, payload["writeBack"]["sourceGuard"]["source_sentence_id"])

    def test_skips_best_sentence_already_present_in_task_center(self):
        rows = worker.build_tts_result_rows(
            task_config(),
            ["public.word_clean_best_sentence"],
            [best_sentence()],
            {11},
            worker.RECORD_TYPE_FORMAL,
        )

        self.assertEqual([], rows)

    def test_fetch_reads_best_sentence_directly_without_legacy_job_status(self):
        cursor = MagicMock()
        cursor.fetchall.return_value = [(
            11,
            7,
            "example",
            "例子",
            101,
            "sentence-model",
            "This is an example.",
            "这是一个例子。",
            96,
            "表达自然",
            "judge-model",
            datetime(2026, 7, 15, 9, 0, tzinfo=timezone.utc),
        )]
        cursor_context = MagicMock()
        cursor_context.__enter__.return_value = cursor
        connection = MagicMock()
        connection.cursor.return_value = cursor_context
        connection_context = MagicMock()
        connection_context.__enter__.return_value = connection

        with patch.object(worker, "connect_source_database", return_value=connection_context):
            rows = worker.fetch_word_clean_best_sentences(MagicMock())

        query = cursor.execute.call_args.args[0].lower()
        self.assertIn("from public.word_clean_best_sentence", query)
        self.assertNotIn("word_clean_sentence_tts_job", query)
        self.assertNotIn("tts_status", query)
        self.assertEqual(11, rows[0]["bestSentenceId"])
        self.assertEqual("2026-07-15T09:00:00+00:00", rows[0]["scoredAt"])

    def test_dispatches_tts_task_config_to_tts_generator(self):
        expected = {"mode": worker.RESULT_MODE_TTS, "insertedCount": 3}
        generate_tts = MagicMock(return_value=expected)
        registry = worker.TaskHandlerRegistry()
        registry.register(worker.TaskHandlerDefinition(
            worker.HANDLER_TTS,
            "AUDIO_TTS",
            result_generator=generate_tts,
        ))
        with (
            patch.object(worker, "load_task_config_snapshot", return_value=task_config()),
            patch.object(worker, "TASK_HANDLER_REGISTRY", registry),
        ):
            result = worker.generate_results_for_task_config(
                1,
                False,
                worker.RECORD_TYPE_VALIDATION_CURRENT,
                3,
            )

        self.assertEqual(expected, result)
        generate_tts.assert_called_once_with(1, False, worker.RECORD_TYPE_VALIDATION_CURRENT, 3)


if __name__ == "__main__":
    unittest.main()
