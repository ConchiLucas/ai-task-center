# TTS MinIO End-to-End Success Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Follow superpowers:test-driven-development for every production behavior change.

**Goal:** Make task-config-4 TTS results succeed only after MiMo generation, MinIO upload verification, source-table backfill, and task-result persistence all complete, while retrying only the current 210 failed results and leaving 21,888 historical successes untouched.

**Architecture:** Java and React own a masked, database-backed object-storage configuration. Python Worker loads the exact configuration referenced by a result/run storage snapshot, synthesizes WAV bytes in memory, stores and verifies a deterministic MinIO object, backfills `public.word_clean_tts`, then returns a successful result payload. Queue persistence remains the final authority for `tb_task_result.status`. A Worker read-only proxy streams MinIO audio for the React detail view.

**Tech Stack:** Java 17, Spring Boot 3/JPA, React 18/TypeScript/Ant Design, Python 3/FastAPI/psycopg2/MinIO SDK, PostgreSQL, Docker MinIO.

---

### Task 1: Add masked object-storage configuration to Java

**Files:**
- Create: `src/main/java/com/aitaskcenter/model/ObjectStorageConfig.java`
- Create: `src/main/java/com/aitaskcenter/repository/ObjectStorageConfigRepository.java`
- Create: `src/main/java/com/aitaskcenter/dto/ObjectStorageConfigRequest.java`
- Create: `src/main/java/com/aitaskcenter/dto/ObjectStorageConfigResponse.java`
- Create: `src/main/java/com/aitaskcenter/service/ObjectStorageConfigService.java`
- Create: `src/main/java/com/aitaskcenter/controller/ObjectStorageConfigController.java`
- Create: `src/test/java/com/aitaskcenter/service/ObjectStorageConfigServiceTest.java`
- Create: `src/test/java/com/aitaskcenter/controller/ObjectStorageConfigControllerTest.java`

- [x] Write RED service tests for required fields, `MINIO` normalization, one enabled default, blank-secret update preservation, and disabled-default rejection.
- [x] Write RED controller/response tests proving list/create/update responses never contain `secretKey` plaintext and expose only `secretConfigured`.
- [x] Run `mvn -Dtest=ObjectStorageConfigServiceTest,ObjectStorageConfigControllerTest test` and confirm the tests fail because the types do not exist.
- [x] Implement the entity, repository, request/response DTOs, transactional service, and `/api/object-storage-config` CRUD/default endpoints using existing response conventions.
- [x] Make default replacement atomic by clearing any previous default in the same transaction before saving the new enabled default.
- [x] Re-run the focused Maven tests and confirm GREEN (6 tests).
- [x] Commit only Task 1 files: `feat: add object storage configuration` (`bbb73e73`).

### Task 2: Add object-storage configuration UI

**Files:**
- Modify: `web-react/src/api.ts` (this project keeps API types in the same module; there is no `types.ts`)
- Modify: `web-react/src/App.tsx`
- Create: `web-react/src/objectStorageForm.ts`
- Create: `web-react/tests/objectStorageForm.test.mjs`

- [x] Add `ObjectStorageConfig` types that model `secretConfigured` without a returned secret value.
- [x] Add list/create/update/delete/default API calls against the Java endpoints.
- [x] Add an “对象存储” tab under configuration management with list, edit modal, enabled/default state, and masked secret placeholder.
- [x] Require name, endpoint, access key, bucket, and base path; require secret on create, but submit blank on edit to preserve the existing secret.
- [x] Prevent choosing default while disabled and show a clear explanation that this config is a task-handler dependency, not an AI invocation channel.
- [x] Run 3 Node form-semantic tests and `npm run build` in `web-react`; both pass.
- [x] Commit only Task 2 files: `feat: manage object storage in web console` (`a1cc5949`).

### Task 3: Build the Python MinIO storage adapter with TDD

**Files:**
- Modify: `python-worker/requirements.txt`
- Create: `python-worker/app/object_storage.py`
- Create: `python-worker/tests/test_object_storage.py`

- [x] Write RED tests for database row mapping, secret-safe representation, exact snapshot validation, safe deterministic key construction, valid WAV enforcement, and MD5/size metadata.
- [x] Write RED adapter tests with a fake MinIO client for missing object upload, exact-object reuse, mismatched-object overwrite, and ETag/MD5 mismatch.
- [x] Run the focused object-storage module and confirm RED because `app.object_storage` did not exist.
- [x] Add pinned `minio==7.2.20` and implement focused dataclasses/functions in `app/object_storage.py`; credentials are excluded from representation.
- [x] Re-run the focused tests and confirm GREEN (12 tests).
- [x] Install the pinned dependency only in `python-worker/.venv` and verify SDK/module imports.
- [x] Commit only Task 3 files: `feat: add verified MinIO storage adapter` (`e0fbb058`).

### Task 4: Add strict storage snapshots to task-config-4 result generation

**Files:**
- Modify: `python-worker/app/main.py`
- Modify: `python-worker/tests/test_task_config_4_result_generation.py`
- Modify: `python-worker/tests/test_task_config_4_batch_execution.py`
- Modify: `python-worker/tests/test_task_handler_catalog.py`

- [x] Write RED generation tests requiring `storageTarget.storageConfigId/providerType/bucket/objectPrefix` in every newly generated task-config-4 result.
- [x] Write RED batch-prompt tests requiring the same storage snapshot and rejecting mixed/missing result snapshots.
- [x] Write RED execution tests rejecting differences among result snapshot, run prompt snapshot, and live database configuration.
- [x] Run the focused task-config-4 tests and confirm four expected failures for missing storage snapshot behavior.
- [x] Load the single enabled default config during result generation and add the immutable storage snapshot to result JSON.
- [x] Propagate the exact snapshot to the batch prompt and validate equality at execution; no environment or adjacent-project fallback exists.
- [x] Re-run focused tests and confirm GREEN (21 task-config-4 tests; 33 with storage adapter regression).
- [x] Commit Task 4 together with the pre-existing same-scope task-config-4 handler base: `081cffd1`.

### Task 5: Make TTS processing end-to-end and rate-limit aware

**Files:**
- Modify: `python-worker/app/main.py`
- Modify: `python-worker/app/object_storage.py`
- Modify: `python-worker/tests/test_mimo_tts_execution.py`
- Modify: `python-worker/tests/test_task_config_4_batch_execution.py`
- Modify: `python-worker/tests/test_tts_batch_execution.py`

- [x] Write RED tests that MiMo generation returns validated WAV bytes without permanent local persistence for task config 4.
- [x] Write RED tests that a result is `SUCCESS` only after verified upload and successful business backfill; MinIO or backfill failure produces `FAILED` with a specific `failureStage`.
- [x] Write RED 429 tests for numeric/date `Retry-After`, bounded exponential fallback, maximum attempts, and injected sleep.
- [x] Run focused tests and confirm seven expected missing-behavior failures plus a separate global-concurrency RED test.
- [x] Split MiMo response decoding from persistence, validate RIFF/WAVE before storage, upload/verify, backfill source, then construct final `ttsResult` and success tuple.
- [x] Keep legacy best-sentence/local-file behavior through the existing `generate_mimo_tts` compatibility wrapper and regression tests.
- [x] Configure task-config-4 batch and Worker-process-wide MiMo execution with concurrency 1 and bounded retry; provider never switches.
- [x] Ensure failure payloads contain `failureStage` but no credentials.
- [x] Re-run focused tests and confirm GREEN (48 tests plus compileall).
- [x] Commit Task 5 files: `fix: require verified MinIO upload for TTS success` (`e2acd8aa`).

### Task 6: Add a read-only MinIO audio proxy

**Files:**
- Modify: `python-worker/app/main.py`
- Create: `python-worker/tests/test_minio_audio_proxy.py`
- Modify: `web-react/src/App.tsx` only if the existing `downloadUrl` normalization requires adjustment.

- [x] Write RED tests for configured bucket/key streaming, `audio/wav`, missing object 404, invalid path 400, and bucket/config rejection.
- [x] Implement `/api/tts/storage/{storage_config_id}/{bucket}/{object_key:path}` as a read-only stream with path/prefix validation and guaranteed response close/release.
- [x] Set new result `downloadUrl` to the Worker proxy and retain business `objectUrl` as `/ai-file-navigation/word_clean_tts/<file>`.
- [x] Run 5 proxy tests plus related regressions and compileall; React requires no change because it already uses `downloadUrl`.
- [x] Commit Task 6 files: `feat: proxy MinIO TTS audio through worker` (`a106ff86`).

### Task 7: Run full automated verification and restart services

**Files:**
- Modify only files required by test failures in the feature scope.

- [x] Run `mvn test` and record the passing test count (58 passed; standard command works on Java 26).
- [x] Run `python-worker/.venv/bin/python -m pytest -q python-worker/tests` and record the passing test count (94 passed after final regression).
- [x] Run `python-worker/.venv/bin/python -m compileall -q python-worker/app python-worker/tests`.
- [x] Run `npm run build` in `web-react` and the 3 object-storage form tests.
- [x] Run `git diff --check` and inspect `git diff --stat` plus all feature diffs for secrets and unrelated files.
- [x] Restart with `./scripts/start-dev.sh` and verify frontend `19637`, Java `18743`, Worker `19186`, and queue scheduler health.
- [x] Register the existing Docker MinIO as enabled default config ID 1 without printing its Secret Key.
- [x] Upload/read/stat/proxy/delete a dedicated smoke-test object; no MiMo invocation.

### Task 8: Backup and retry exactly the 210 failed results

**Files:**
- Create runtime artifacts under: `/private/tmp/ai-task-center-tts-retry-20260719/`
- Do not modify task-workflow scripts.

- [x] Re-query the candidate set with all guards and assert the exact audited 210 sorted IDs.
- [x] Export complete result/source rows and sorted IDs; calculate SHA-256 backup artifacts.
- [x] In one transaction, add storage snapshot only to the exact 210 failed payloads; historical 21,888-row SHA-256 remained unchanged.
- [x] Create task run 8268 with only those 210 IDs, concurrency 1, and strict handler/executor/storage snapshots.
- [x] Monitor the run to `SUCCESS` on attempt 1; no prior success result was included.
- [x] Verify all 210 across result rows, source rows, MinIO full-byte WAV/size/MD5/ETag, and Worker proxy samples.
- [x] Final counts: 210 success, 0 failure, 0 missing object, 0 size mismatch, 0 metadata mismatch, 21,888 historical results untouched.
- [x] Preserve backups and final verification report under `/private/tmp/ai-task-center-tts-retry-20260719/` with SHA-256 hashes.

### Task 9: Final review and handoff

**Files:**
- Modify: `task_plan.md`
- Modify: `findings.md`
- Modify: `progress.md`

- [x] Perform a focused self-review for secret exposure, transaction boundaries, status ordering, retry scope, and accidental historical migration (subagent delegation prohibited in this session).
- [x] Re-run decisive verification after review changes: standard `mvn test` 58/58; Python 94/94; frontend tests/build; runtime and data audit.
- [x] Update the planning files with final evidence; remaining failed IDs: none.
- [x] Commit the planning record separately without adding `data/tts_audio` or unrelated user changes.
- [x] Summarize implementation, service state, 210-result outcome, untouched 21,888 baseline, artifact paths, and SHA-256 values.
