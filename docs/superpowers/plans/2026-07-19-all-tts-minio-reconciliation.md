# All TTS MinIO Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make both formal TTS handlers succeed only after a verified MinIO object and source backfill exist, repair the exact ten missing best-sentence audios, synchronize 21,888 stale task-config-4 result snapshots, and report 276 unreferenced objects without deletion.

**Architecture:** Extend the existing Python Worker TTS pipeline instead of introducing another service: result generation captures a strict object-storage snapshot, batch execution validates result/run/live snapshots, MiMo returns in-memory WAV bytes, the existing MinIO adapter stores and verifies them, and only then does the source database and task result move to success. Put deterministic reconciliation transforms and guards in a focused module so one-time database maintenance is testable; run the two mutations as separately backed-up transactions.

**Tech Stack:** Python 3.13, FastAPI, psycopg2, MinIO Python SDK, unittest/pytest, Java 26/Spring Boot/Maven, React/Vitest/Vite, PostgreSQL 17, Docker MinIO.

---

## File map

- Modify `python-worker/app/main.py`: best-sentence result snapshots, strict storage validation, in-memory MiMo execution, MinIO persistence, source backfill ordering, and failure stages.
- Create `python-worker/app/tts_reconciliation.py`: pure guarded transforms for exact repair preparation, task-config-4 snapshot synchronization, and orphan classification.
- Modify `python-worker/tests/test_tts_result_generation.py`: storage snapshot requirements for newly generated best-sentence results.
- Modify `python-worker/tests/test_tts_batch_execution.py`: strict best-sentence execution contract and failure behavior.
- Create `python-worker/tests/test_tts_reconciliation.py`: exact-set guards and deterministic snapshot/orphan transforms.
- Create `docs/verification/2026-07-19-all-tts-minio-reconciliation.md`: commands, backup hashes, mutation counts, and final consistency evidence without secrets.
- Modify `task_plan.md`, `findings.md`, and `progress.md`: durable execution status and audit findings.

### Task 1: Capture MinIO in new best-sentence result snapshots

**Files:**
- Modify: `python-worker/tests/test_tts_result_generation.py`
- Modify: `python-worker/app/main.py:1963-2070`

- [ ] **Step 1: Write failing result-generation tests**

Add tests that patch `load_default_object_storage_config_snapshot()` and assert both the stored result and batch prompt receive the same snapshot:

```python
STORAGE_TARGET = {
    "storageConfigId": 1,
    "providerType": "MINIO",
    "bucket": "ai-file-navigation",
    "objectPrefix": "word_clean_tts",
}

def test_best_sentence_tts_result_contains_strict_storage_target():
    payload = worker.build_tts_result_payload(best_sentence(), STORAGE_TARGET)
    assert payload["storageTarget"] == STORAGE_TARGET
    assert payload["ttsInput"]["fileName"] == "word_clean_7_best_101.wav"

def test_best_sentence_tts_result_rows_require_storage_snapshot():
    rows = worker.build_tts_result_rows(config(), ["public.word_clean_best_sentence"], [best_sentence()], set(), "FORMAL", STORAGE_TARGET)
    assert json.loads(rows[0][12])["storageTarget"] == STORAGE_TARGET
```

- [ ] **Step 2: Run the tests and observe RED**

Run: `python-worker/.venv/bin/python -m pytest python-worker/tests/test_tts_result_generation.py -q`

Expected: FAIL because `build_tts_result_payload` and `build_tts_result_rows` do not accept or store `storage_target`.

- [ ] **Step 3: Add the minimal snapshot parameters**

Implement these signatures and copy the normalized dictionary into the payload:

```python
def build_tts_result_payload(best_sentence: dict[str, Any], storage_target: dict[str, Any]) -> dict[str, Any]:
    ...
    return {
        "taskType": RESULT_MODE_TTS,
        "storageTarget": dict(storage_target),
        ...
    }

def build_tts_result_rows(..., record_type: str, storage_target: dict[str, Any]) -> list[tuple[Any, ...]]:
    ...
    payload = build_tts_result_payload(best_sentence, storage_target)
```

At the best-sentence generation endpoint, call `load_default_object_storage_config_snapshot()` once and pass its public snapshot to every generated row. Reject an absent/inactive/non-MinIO default rather than producing incomplete results.

- [ ] **Step 4: Make the focused tests GREEN**

Run: `python-worker/.venv/bin/python -m pytest python-worker/tests/test_tts_result_generation.py -q`

Expected: all tests PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add python-worker/app/main.py python-worker/tests/test_tts_result_generation.py
git commit -m "feat: snapshot MinIO for best sentence TTS"
```

### Task 2: Enforce three-way storage identity in best-sentence batches

**Files:**
- Modify: `python-worker/tests/test_tts_batch_execution.py`
- Modify: `python-worker/app/main.py:3740-4195`

- [ ] **Step 1: Write failing snapshot-validation tests**

Add `storageTarget` to `result_snapshot()` and `run_snapshot()`, then cover exact match and drift:

```python
def test_best_sentence_storage_context_matches_result_run_and_live_config():
    with patch.object(worker, "load_object_storage_config_snapshot", return_value=storage_config()):
        target, loaded = worker.resolve_tts_storage_context(result_snapshot(41, 101, 7), run_snapshot())
    assert target == STORAGE_TARGET
    assert loaded.bucket_name == "ai-file-navigation"

def test_best_sentence_storage_context_rejects_run_drift():
    run = run_snapshot()
    prompt = json.loads(run.ai_prompt_json)
    prompt["batch"]["storageTarget"]["bucket"] = "other"
    run = replace(run, ai_prompt_json=json.dumps(prompt))
    with pytest.raises(HTTPException, match="存储快照不一致"):
        worker.resolve_tts_storage_context(result_snapshot(41, 101, 7), run)
```

Also assert the result ID/source IDs in each prompt item match the associated task result, so reordering cannot execute a different source.

- [ ] **Step 2: Run the tests and observe RED**

Run: `python-worker/.venv/bin/python -m pytest python-worker/tests/test_tts_batch_execution.py -q`

Expected: FAIL because the generic best-sentence path neither carries nor validates `storageTarget`.

- [ ] **Step 3: Implement strict storage resolution**

Extract the task-config-4 storage comparison into a generic helper used by both handlers:

```python
def resolve_verified_storage_context(
    result_target: dict[str, Any],
    run_target: dict[str, Any],
) -> tuple[dict[str, Any], ObjectStorageConfigSnapshot]:
    normalized_result = normalize_storage_target(result_target)
    normalized_run = normalize_storage_target(run_target)
    if normalized_result != normalized_run:
        raise HTTPException(status_code=400, detail="任务结果与批次存储快照不一致")
    loaded = load_object_storage_config_snapshot(normalized_result["storageConfigId"])
    validate_live_storage_target(normalized_result, loaded)
    return normalized_result, loaded
```

Keep `resolve_task_config_4_storage_context()` as a thin compatibility wrapper and add `resolve_tts_storage_context()` for best-sentence results. The batch parser must require the batch-level and item-level snapshots and exact source IDs.

- [ ] **Step 4: Make snapshot tests GREEN**

Run: `python-worker/.venv/bin/python -m pytest python-worker/tests/test_tts_batch_execution.py python-worker/tests/test_task_config_4_batch_execution.py -q`

Expected: all tests PASS with task-config-4 behavior unchanged.

- [ ] **Step 5: Commit Task 2**

```bash
git add python-worker/app/main.py python-worker/tests/test_tts_batch_execution.py
git commit -m "feat: validate best sentence storage snapshots"
```

### Task 3: Store and verify best-sentence WAVs before success

**Files:**
- Modify: `python-worker/tests/test_tts_batch_execution.py`
- Modify: `python-worker/app/main.py:3420-4195`

- [ ] **Step 1: Write failing success-path test**

Mock only the network and databases; assert operation order and result fields:

```python
def test_formal_best_sentence_tts_requires_verified_minio_then_backfill():
    events = []
    generated = GeneratedTtsAudio("xiaomi-mimo-tts", "mimo-v2.5-tts", "Chloe", "wav", "best-101.wav", WAV_BYTES)
    stored = StoredWav("ai-file-navigation", "word_clean_tts/best-101.wav", "http://minio/object", len(WAV_BYTES), MD5)
    with patch.object(worker, "generate_serialized_mimo_tts_audio", side_effect=lambda *_: events.append("mimo") or generated), \
         patch.object(worker, "store_verified_wav", side_effect=lambda *_: events.append("minio") or stored), \
         patch.object(worker, "backfill_word_clean_best_sentence_tts", side_effect=lambda *_: events.append("backfill") or {"sourceGuardMatched": True}), \
         patch.object(worker, "resolve_tts_storage_context", return_value=(STORAGE_TARGET, storage_config())):
        row, response = worker.process_tts_batch_item(result_snapshot(41, 101, 7), run_snapshot(), "item_A")
    assert events == ["mimo", "minio", "backfill"]
    assert row[1] == "SUCCESS"
    assert row[3]["ttsResult"]["storageVerified"] is True
    assert row[3]["ttsResult"]["downloadUrl"].startswith("http://127.0.0.1:19186/api/tts/storage/")
    assert "processorError" not in row[3]
    assert "failureStage" not in row[3]
```

- [ ] **Step 2: Write failing failure-stage tests**

Parameterize failures for snapshot, MiMo, upload verification, source guard, and result persistence. Assert the row is `FAILED`, `failureStage` is respectively `SNAPSHOT_VALIDATION`, `MIMO_TTS`, `MINIO_UPLOAD`, `SOURCE_BACKFILL`, or `RESULT_PERSISTENCE`, and no later side effect is called. Add a validation-record test that uploads/verifies MinIO but never loads or writes the source database.

- [ ] **Step 3: Run the tests and observe RED**

Run: `python-worker/.venv/bin/python -m pytest python-worker/tests/test_tts_batch_execution.py -q`

Expected: FAIL because the current path calls `generate_mimo_tts()`, writes a local file, and has no MinIO validation/failure stages.

- [ ] **Step 4: Implement the ordered in-memory pipeline**

Use one global MiMo lock for both TTS handlers:

```python
def generate_serialized_mimo_tts_audio(tts_input: dict[str, Any], provider_id: str) -> GeneratedTtsAudio:
    with MIMO_TTS_LOCK:
        return generate_mimo_tts_audio(tts_input, provider_id, strict_provider=True)
```

In `process_tts_batch_item`, track `failure_stage`, call the strict storage resolver, generate bytes, construct the existing MinIO client, call `store_verified_wav`, build the stored result with proxy URL, then backfill formal data. Success must remove stale `processorError`, `failureStage`, and stale top-level failure fields. Validation data performs every storage check but sets `backfillResult.skipped=true`.

- [ ] **Step 5: Force worker count one for this MiMo repair path**

Set best-sentence batch `worker_count = 1`; keep per-item continuation. Assert the run response reports one even if `requested_worker_count` is greater.

- [ ] **Step 6: Make execution tests GREEN**

Run: `python-worker/.venv/bin/python -m pytest python-worker/tests/test_tts_batch_execution.py python-worker/tests/test_task_config_4_batch_execution.py python-worker/tests/test_object_storage.py -q`

Expected: all tests PASS and neither handler regresses.

- [ ] **Step 7: Commit Task 3**

```bash
git add python-worker/app/main.py python-worker/tests/test_tts_batch_execution.py
git commit -m "feat: verify best sentence TTS in MinIO"
```

### Task 4: Add guarded reconciliation transforms

**Files:**
- Create: `python-worker/app/tts_reconciliation.py`
- Create: `python-worker/tests/test_tts_reconciliation.py`

- [ ] **Step 1: Write RED tests for exact-set and source guards**

Define the immutable expected repair mapping of ten IDs in the test and assert `validate_best_sentence_repair_set()` rejects a missing row, extra row, changed word, changed source ID, or an object that already exists.

- [ ] **Step 2: Write RED tests for task-config-4 snapshot sync**

Test a pure transform:

```python
updated = build_synced_task_config_4_payload(old_payload, source_row, object_stat, proxy_base_url)
assert updated["storageTarget"]["bucket"] == "ai-file-navigation"
assert updated["ttsResult"]["objectKey"] == source_row["tts_object_key"]
assert updated["ttsResult"]["fileSize"] == object_stat["size"]
assert updated["ttsResult"]["etag"] == object_stat["etag"]
assert updated["ttsResult"]["storageVerified"] is True
assert updated["ttsResult"]["downloadUrl"].startswith(f"{proxy_base_url}/api/tts/storage/")
```

Assert it rejects a non-success result, local/source ID mismatch, duplicate source ID, missing object, and size mismatch, while leaving all unrelated payload fields byte-for-byte equivalent after canonical JSON comparison.

- [ ] **Step 3: Write RED orphan-classification tests**

Given current references, historical result identities, and object names, assert the function returns exactly `historical_result`, `current_source_unreferenced`, or `unmapped`; it must expose no delete operation.

- [ ] **Step 4: Run the tests and observe RED**

Run: `python-worker/.venv/bin/python -m pytest python-worker/tests/test_tts_reconciliation.py -q`

Expected: FAIL because `app.tts_reconciliation` does not exist.

- [ ] **Step 5: Implement pure guarded functions**

Create frozen dataclasses for object facts and repair identities plus these functions:

```python
def validate_best_sentence_repair_set(rows: Sequence[BestSentenceRepairFact], expected: Mapping[int, BestSentenceRepairIdentity]) -> None: ...
def build_synced_task_config_4_payload(payload: Mapping[str, Any], source: TaskConfig4SourceFact, object_stat: ObjectFact, proxy_base_url: str) -> dict[str, Any]: ...
def validate_unique_source_ids(rows: Sequence[TaskConfig4SourceFact]) -> None: ...
def classify_orphan_object(object_key: str, current_refs: set[str], historical_refs: Mapping[str, list[int]], current_source_ids: Mapping[str, int]) -> OrphanClassification: ...
```

All guards raise `ReconciliationGuardError`; no function opens a database, calls MiMo, uploads, or deletes.

- [ ] **Step 6: Make reconciliation tests GREEN and commit**

Run: `python-worker/.venv/bin/python -m pytest python-worker/tests/test_tts_reconciliation.py -q`

Expected: all tests PASS.

```bash
git add python-worker/app/tts_reconciliation.py python-worker/tests/test_tts_reconciliation.py
git commit -m "feat: add guarded TTS reconciliation transforms"
```

### Task 5: Run automated verification and restart services

**Files:**
- Modify only if tests expose defects in files already listed.

- [ ] **Step 1: Run Python verification**

```bash
python-worker/.venv/bin/python -m pytest python-worker/tests -q
python-worker/.venv/bin/python -m compileall -q python-worker/app python-worker/tests
```

Expected: all Python tests PASS; compileall exits 0.

- [ ] **Step 2: Run Java and React verification**

```bash
cd java-server && mvn test
cd react-ui && npm test -- --run && npm run build
```

Expected: Java 58 or more tests PASS; React tests PASS; Vite build succeeds.

- [ ] **Step 3: Run repository checks**

Run: `git diff --check && zsh -n scripts/start-dev.sh`

Expected: both commands exit 0.

- [ ] **Step 4: Restart with the documented launcher**

Run: `./scripts/start-dev.sh`

Expected: frontend `19637`, Java `18743`, and Worker `19186` are healthy.

- [ ] **Step 5: Run a temporary-object MinIO smoke test**

Generate a tiny valid WAV in memory, upload/stat/read through Worker proxy, compare byte size and MD5/ETag, then delete only that uniquely named smoke object. Do not invoke MiMo.

### Task 6: Backup and repair the exact ten best-sentence TTS records

**Files:**
- Create runtime artifacts under: `/private/tmp/ai-task-center-tts-reconciliation-20260719/best-sentence-10/`
- Modify: `docs/verification/2026-07-19-all-tts-minio-reconciliation.md`

- [ ] **Step 1: Re-run the read-only preflight**

Assert exact task-result IDs `{67373,68521,69951,70626,70921,77650,78873,80900,82218,85735}`, exact best-sentence IDs `{160,1308,2738,3413,3708,10662,11885,13912,15230,18747}`, expected words/statuses, missing local files, and missing deterministic MinIO keys. Abort on any difference.

- [ ] **Step 2: Export complete backups and hashes**

Export complete JSON rows for the ten task results, ten source rows, all related run-result links and runs, exact ID manifests, and MinIO stat absence evidence. Produce `SHA256SUMS` using `shasum -a 256`; verify it with `shasum -a 256 -c SHA256SUMS`.

- [ ] **Step 3: Prepare only the ten results in one transaction**

Within a transaction, lock and revalidate the ten rows, add the exact live `storageTarget` to all ten result payloads, change only the seven false-success results to `FAILED` with audit reason `来源和 MinIO 校验不一致，已转入精确修复`, keep the three failures executable, and reset only source ID 1308 from `running` to `pending`. Assert row counts 10, 7, and 1 before commit.

- [ ] **Step 4: Submit one existing batch with concurrency one**

Use `/api/task-result/batch-process` with exactly the ten ascending result IDs, formal data, and `workerCount=1`. Record returned run/batch IDs. Do not call a validation endpoint and do not include any other result.

- [ ] **Step 5: Monitor to terminal state**

Poll the existing queue/run endpoints until all ten are terminal. Record per-item status/failure stage without printing credentials. If any item fails, stop before Task 7 and retain successful objects.

- [ ] **Step 6: Verify all ten end-to-end**

For every row assert result `SUCCESS`, source `tts_status=success`, MinIO URL/key, valid RIFF/WAVE bytes, DB/object sizes equal, MD5 equals ETag, and Worker proxy bytes equal MinIO. Confirm no long-term file appeared under `python-worker/data/tts_audio`.

### Task 7: Synchronize exactly 21,888 task-config-4 snapshots

**Files:**
- Create runtime artifacts under: `/private/tmp/ai-task-center-tts-reconciliation-20260719/task-config-4-21888/`
- Modify: `docs/verification/2026-07-19-all-tts-minio-reconciliation.md`

- [ ] **Step 1: Select and guard the exact population**

Select formal task-config-4 `SUCCESS` results whose `ttsResult.downloadUrl` is the legacy local Worker route and whose `wordCleanTtsId` joins one-to-one to `public.word_clean_tts`. Require exactly 21,888 unique results and source IDs; reject any non-success or already-MinIO result.

- [ ] **Step 2: Back up result rows and canonical hashes**

Export complete result rows, pre-update `result_content`, canonical per-result JSON SHA-256, exact ID list, joined source facts, and MinIO stat facts. Verify the backup manifest before mutation.

- [ ] **Step 3: Validate every existing object**

For all 21,888, require object existence, source URL/key agreement, positive size, DB/object size agreement, and an ETag. This step performs no MiMo call and no upload.

- [ ] **Step 4: Update only result_content in one transaction**

Use `build_synced_task_config_4_payload()` for each row. Lock the exact IDs; update only `tb_task_result.result_content`; leave `status`, summaries, errors, timestamps, source rows, runs, batches, and links unchanged. Assert exactly 21,888 updates before commit.

- [ ] **Step 5: Verify invariant hashes and new storage fields**

Assert 22,098 task-config-4 formal success results now carry MinIO storage snapshots and legacy download URL count is zero. Recompute hashes for every protected non-`result_content` column and all runs/links; require equality with backup. Sample Worker proxy playback across the ID range.

### Task 8: Produce orphan report and final consistency audit

**Files:**
- Create: `/private/tmp/ai-task-center-tts-reconciliation-20260719/orphans-276.json`
- Modify: `docs/verification/2026-07-19-all-tts-minio-reconciliation.md`
- Modify: `task_plan.md`
- Modify: `findings.md`
- Modify: `progress.md`

- [ ] **Step 1: Classify the 276 unreferenced objects read-only**

List `ai-file-navigation/word_clean_tts/`, subtract both current business-table references, require exactly 276, and classify with `classify_orphan_object()`. Save bucket/key/size/etag and historical IDs only. Call no delete API.

- [ ] **Step 2: Run the full final audit**

Require:

```text
word_clean_tts: 22098/22098 success and verified MinIO
word_clean_best_sentence: 22098/22098 success and verified MinIO
current unique business references: 44196
missing objects: 0
DB/object size mismatches: 0
URL/key mismatches: 0
task_config_1 exact repaired results: 10/10 SUCCESS
task_config_4 MinIO snapshots: 22098/22098
legacy task_config_4 local URLs: 0
MinIO objects deleted: 0
```

- [ ] **Step 3: Record evidence and artifact hashes**

Write exact commands, counts, run IDs, backup paths, SHA-256 values, test counts, and known collation warning to the verification document. Never include MiMo or MinIO secrets.

- [ ] **Step 4: Run final tests and repository checks**

```bash
python-worker/.venv/bin/python -m pytest python-worker/tests -q
cd java-server && mvn test
cd react-ui && npm test -- --run && npm run build
git diff --check
```

Expected: every command succeeds.

- [ ] **Step 5: Commit documentation and durable progress**

```bash
git add docs/verification/2026-07-19-all-tts-minio-reconciliation.md task_plan.md findings.md progress.md
git commit -m "docs: verify all TTS MinIO consistency"
```

