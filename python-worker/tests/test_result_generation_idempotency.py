import json
import unittest

from app.result_generation_idempotency import (
    advisory_lock_key,
    build_recovered_generation_response,
    execute_onboarding_generation_transaction,
    merge_onboarding_generation_metadata,
    managed_connection,
    normalize_onboarding_generation_id,
)


GENERATION_ID = "b" * 64


class ResultGenerationIdempotencyTest(unittest.TestCase):
    def test_committed_existing_rows_return_recovered_insert_count(self) -> None:
        existing_contents = [
            json.dumps({"taskType": "score", "_meta": {"onboardingGenerationId": GENERATION_ID}}),
            "not-json",
            json.dumps({"_meta": {"onboardingGenerationId": "c" * 64}}),
            json.dumps({"_meta": {"onboardingGenerationId": GENERATION_ID}}),
        ]

        response = build_recovered_generation_response(7, GENERATION_ID, existing_contents)

        self.assertEqual(2, response["insertedCount"])
        self.assertTrue(response["accepted"])
        self.assertTrue(response["recovered"])
        self.assertEqual(7, response["taskConfigId"])

    def test_new_result_metadata_preserves_existing_meta(self) -> None:
        payload = {
            "taskType": "word_clean_sentence_score",
            "_meta": {"artifactVersion": 3},
        }

        merged = merge_onboarding_generation_metadata(payload, GENERATION_ID)

        self.assertEqual(3, merged["_meta"]["artifactVersion"])
        self.assertEqual(GENERATION_ID, merged["_meta"]["onboardingGenerationId"])
        self.assertEqual({"artifactVersion": 3}, payload["_meta"])

    def test_generation_id_is_optional_but_must_be_lowercase_sha256_shape(self) -> None:
        self.assertIsNone(normalize_onboarding_generation_id(None))
        self.assertIsNone(normalize_onboarding_generation_id("  "))
        self.assertEqual(GENERATION_ID, normalize_onboarding_generation_id(GENERATION_ID))
        with self.assertRaises(ValueError):
            normalize_onboarding_generation_id("B" * 64)
        with self.assertRaises(ValueError):
            normalize_onboarding_generation_id("short")

    def test_advisory_lock_recovery_and_insert_share_one_transaction(self) -> None:
        connection = FakeConnection([])
        inserted_on = []

        response = execute_onboarding_generation_transaction(
            connection,
            7,
            GENERATION_ID,
            "word_clean_sentence_score_generation",
            lambda: [("row",)],
            lambda target, rows: inserted_on.append((target, rows)) or len(rows),
        )

        self.assertEqual(1, response["insertedCount"])
        self.assertEqual([(connection, [("row",)])], inserted_on)
        self.assertEqual(1, connection.commits)
        self.assertIn("pg_advisory_xact_lock", normalized(connection.statements[0][0]))
        self.assertEqual((advisory_lock_key(7, GENERATION_ID),), connection.statements[0][1])
        self.assertIn("select result_content", normalized(connection.statements[1][0]))
        self.assertIn("result_content like", normalized(connection.statements[1][0]))
        self.assertIn(GENERATION_ID, connection.statements[1][1])

    def test_serialized_retry_recovers_without_second_insert(self) -> None:
        existing = [
            (json.dumps({"_meta": {"onboardingGenerationId": GENERATION_ID}}),),
            (json.dumps({"_meta": {"onboardingGenerationId": GENERATION_ID}}),),
        ]
        connection = FakeConnection(existing)
        insert_calls = []

        response = execute_onboarding_generation_transaction(
            connection,
            7,
            GENERATION_ID,
            "word_clean_sentence_score_generation",
            lambda: self.fail("recovered generation must not build rows"),
            lambda target, rows: insert_calls.append(rows) or len(rows),
        )

        self.assertEqual(2, response["insertedCount"])
        self.assertTrue(response["recovered"])
        self.assertEqual([], insert_calls)
        self.assertEqual(1, connection.commits)

    def test_managed_connection_always_closes_after_transaction_scope(self) -> None:
        connection = FakeManagedConnection()
        def factory(**kwargs):
            connection.opened_with = kwargs
            return connection

        with managed_connection(factory, application_name="worker") as opened:
            self.assertIs(connection, opened)

        self.assertEqual({"application_name": "worker"}, connection.opened_with)
        self.assertEqual(1, connection.enters)
        self.assertEqual(1, connection.exits)
        self.assertEqual(1, connection.closes)


class FakeConnection:
    def __init__(self, result_rows):
        self.result_rows = result_rows
        self.statements = []
        self.commits = 0

    def cursor(self):
        return FakeCursor(self)

    def commit(self):
        self.commits += 1


class FakeCursor:
    def __init__(self, connection):
        self.connection = connection

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def execute(self, sql, parameters=None):
        self.connection.statements.append((sql, parameters))

    def fetchall(self):
        return self.connection.result_rows


class FakeManagedConnection:
    def __init__(self):
        self.opened_with = None
        self.enters = 0
        self.exits = 0
        self.closes = 0

    def __enter__(self):
        self.enters += 1
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.exits += 1
        return False

    def close(self):
        self.closes += 1


def normalized(sql: str) -> str:
    return " ".join(sql.lower().split())


if __name__ == "__main__":
    unittest.main()
