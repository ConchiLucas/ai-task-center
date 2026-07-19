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

- [ ] Write RED service tests for required fields, `MINIO` normalization, one enabled default, blank-secret update preservation, and disabled-default rejection.
- [ ] Write RED controller/response tests proving list/create/update responses never contain `secretKey` plaintext and expose only `secretConfigured`.
- [ ] Run `mvn -Dtest=ObjectStorageConfigServiceTest,ObjectStorageConfigControllerTest test` and confirm the tests fail because the types do not exist.
- [ ] Implement the entity, repository, request/response DTOs, transactional service, and `/api/object-storage-config` CRUD/default endpoints using existing response conventions.
- [ ] Make default replacement atomic by clearing any previous default in the same transaction before saving the new enabled default.
- [ ] Re-run the focused Maven tests and confirm GREEN.
- [ ] Commit only Task 1 files: `feat: add object storage configuration`.

### Task 2: Add object-storage configuration UI

**Files:**
- Modify: `web-react/src/types.ts`
- Modify: `web-react/src/api.ts`
- Modify: `web-react/src/App.tsx`
- Modify: `web-react/src/styles.css`

- [ ] Add `ObjectStorageConfig` types that model `secretConfigured` without a returned secret value.
- [ ] Add list/create/update/delete/default API calls against the Java endpoints.
- [ ] Add an “对象存储” tab under configuration management with list, edit modal, enabled/default state, and masked secret placeholder.
- [ ] Require name, endpoint, access key, bucket, and base path; require secret on create, but submit blank on edit to preserve the existing secret.
- [ ] Prevent choosing default while disabled and show a clear explanation that this config is a task-handler dependency, not an AI invocation channel.
- [ ] Run `npm run build` in `web-react` and fix all TypeScript/build failures.
- [ ] Commit only Task 2 files: `feat: manage object storage in web console`.

### Task 3: Build the Python MinIO storage adapter with TDD

**Files:**
- Modify: `python-worker/requirements.txt`
- Create: `python-worker/app/object_storage.py`
- Create: `python-worker/tests/test_object_storage.py`

- [ ] Write RED tests for database row mapping, masked/disabled/missing configuration rejection, exact snapshot validation, safe deterministic key construction, valid WAV enforcement, and MD5/size metadata.
- [ ] Write RED adapter tests with a fake MinIO client for missing object upload, exact-object reuse, mismatched-object overwrite, post-upload stat failure, size mismatch, and ETag/MD5 mismatch.
- [ ] Run `python-worker/.venv/bin/python -m pytest -q python-worker/tests/test_object_storage.py` and confirm RED.
- [ ] Add a pinned MinIO SDK dependency and implement focused dataclasses/functions in `app/object_storage.py`; do not log credentials.
- [ ] Re-run the focused tests and confirm GREEN.
- [ ] Install the pinned dependency only in `python-worker/.venv` and verify imports.
- [ ] Commit only Task 3 files: `feat: add verified MinIO storage adapter`.

### Task 4: Add strict storage snapshots to task-config-4 result generation

**Files:**
- Modify: `python-worker/app/main.py`
- Modify: `python-worker/tests/test_task_config_4_result_generation.py`
- Modify: `python-worker/tests/test_task_config_4_batch_execution.py`
- Modify: `python-worker/tests/test_task_handler_catalog.py`

- [ ] Write RED generation tests requiring `storageTarget.storageConfigId/providerType/bucket/objectPrefix` in every newly generated task-config-4 result.
- [ ] Write RED batch-prompt tests requiring the same storage snapshot and rejecting mixed/missing result snapshots.
- [ ] Write RED execution tests rejecting differences among result snapshot, run prompt snapshot, and live database configuration.
- [ ] Run only the three focused task-config-4 test modules and confirm the new assertions fail.
- [ ] Load the single enabled default config during result generation and add the immutable storage snapshot to result JSON.
- [ ] Propagate the exact snapshot to the batch prompt and validate equality at execution; do not fall back to environment variables or another repository.
- [ ] Re-run focused tests and confirm GREEN.
- [ ] Commit only Task 4 changes, preserving the pre-existing task-config-4 work: `feat: snapshot TTS object storage target`.

### Task 5: Make TTS processing end-to-end and rate-limit aware

**Files:**
- Modify: `python-worker/app/main.py`
- Modify: `python-worker/app/object_storage.py`
- Modify: `python-worker/tests/test_mimo_tts_execution.py`
- Modify: `python-worker/tests/test_task_config_4_batch_execution.py`
- Modify: `python-worker/tests/test_tts_batch_execution.py`

- [ ] Write RED tests that MiMo generation returns validated WAV bytes without permanent local persistence for task config 4.
- [ ] Write RED tests that a result is `SUCCESS` only after verified upload and successful business backfill; MinIO failure, verification failure, or backfill failure must produce `FAILED` with a specific `failureStage`.
- [ ] Write RED 429 tests for numeric/date `Retry-After`, bounded exponential fallback, maximum attempts, and injected sleep so unit tests do not wait.
- [ ] Run focused tests and confirm RED.
- [ ] Split MiMo response decoding from persistence, validate RIFF/WAVE before storage, compute metadata, upload/verify, backfill source, then construct final `ttsResult` and success tuple.
- [ ] Keep legacy best-sentence/local-file behavior unchanged unless its tests prove shared helpers need a compatible wrapper.
- [ ] Configure task-config-4 batch execution with concurrency 1 and bounded retry; never automatically switch provider.
- [ ] Ensure failure payloads contain `failureStage` but no credentials or full provider response secrets.
- [ ] Re-run focused tests and confirm GREEN.
- [ ] Commit Task 5 files: `fix: require verified MinIO upload for TTS success`.

### Task 6: Add a read-only MinIO audio proxy

**Files:**
- Modify: `python-worker/app/main.py`
- Create: `python-worker/tests/test_minio_audio_proxy.py`
- Modify: `web-react/src/App.tsx` only if the existing `downloadUrl` normalization requires adjustment.

- [ ] Write RED FastAPI tests for valid configured bucket/key streaming, `audio/wav`, missing object 404, invalid path 400, and mismatched snapshot/config rejection.
- [ ] Implement `/api/tts/storage/{storage_config_id}/{bucket}/{object_key:path}` as a read-only stream with path/prefix validation and guaranteed response close/release.
- [ ] Set new result `downloadUrl` to the Worker proxy and retain business `objectUrl` as `/ai-file-navigation/word_clean_tts/<file>`.
- [ ] Run proxy tests and `npm run build`; confirm GREEN.
- [ ] Commit Task 6 files: `feat: proxy MinIO TTS audio through worker`.

### Task 7: Run full automated verification and restart services

**Files:**
- Modify only files required by test failures in the feature scope.

- [ ] Run `mvn test` and record the passing test count.
- [ ] Run `python-worker/.venv/bin/python -m pytest -q python-worker/tests` and record the passing test count.
- [ ] Run `python-worker/.venv/bin/python -m compileall -q python-worker/app python-worker/tests`.
- [ ] Run `npm run build` in `web-react`.
- [ ] Run `git diff --check` and inspect `git diff --stat` plus all feature diffs for secrets and unrelated files.
- [ ] Restart with `./scripts/start-dev.sh` and verify frontend `19637`, Java `18743`, Worker `19186`, and queue scheduler health.
- [ ] Register the existing Docker MinIO as the enabled default config by reading existing credentials without printing them.
- [ ] Upload/read/stat/delete one dedicated smoke-test object under a non-business prefix; do not invoke MiMo.

### Task 8: Backup and retry exactly the 210 failed results

**Files:**
- Create runtime artifacts under: `/private/tmp/ai-task-center-tts-retry-20260719/`
- Do not modify task-workflow scripts.

- [ ] Re-query the candidate set with all guards: `task_config_id = 4`, `record_type = 'FORMAL'`, `status = 'FAILED'`, and payload/write-back source `public.word_clean_tts`; assert count and sorted IDs equal the audited 210 set.
- [ ] Export full rows/result JSON, exact sorted IDs, source-row guards, and pre-change counts; calculate and record SHA-256 for every backup artifact.
- [ ] In one transaction, add only the approved storage snapshot to those exact failed result payloads; assert affected rows equals 210 and historical-success affected rows equals 0.
- [ ] Create a new task run containing only those 210 result IDs with concurrency 1 and the same handler/executor/storage snapshots.
- [ ] Start the run and monitor it to a terminal state, respecting MiMo bounded 429 retry; do not include any existing success result.
- [ ] Verify every successful item across `tb_task_result`, `public.word_clean_tts`, MinIO stat/MD5/size, and Worker proxy playback.
- [ ] Report counts for success, remaining failure, missing object, size mismatch, metadata mismatch, and untouched historical successes.
- [ ] Preserve all backups and report their absolute paths and SHA-256 hashes.

### Task 9: Final review and handoff

**Files:**
- Modify: `task_plan.md`
- Modify: `findings.md`
- Modify: `progress.md`

- [ ] Perform a focused code review for secret exposure, transaction boundaries, status ordering, retry scope, and accidental historical migration.
- [ ] Re-run the smallest decisive verification if review changes code.
- [ ] Update the planning files with final evidence and any remaining failed IDs.
- [ ] Commit the planning record separately without adding `data/tts_audio` or unrelated user changes.
- [ ] Summarize implementation, service state, 210-result outcome, untouched 21,888 baseline, artifact paths, and SHA-256 values.
