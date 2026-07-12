import json
import unittest

from app.result_generation_idempotency import (
    build_recovered_generation_response,
    merge_onboarding_generation_metadata,
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


if __name__ == "__main__":
    unittest.main()
