# Unified Execution Target Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make task runtime select exactly one CLI or AI Provider execution target while keeping PostgreSQL and Python Worker as the only scheduler, and make TTS runs use and display the configured MiMo Provider.

**Architecture:** Java owns task configuration, validation, queue persistence, and non-secret execution-target catalog metadata. Python Worker resolves the snapshotted target and performs all CLI, LLM API, and TTS calls through normalized executor adapters. Existing `cliId` data remains readable during migration, while new handler and executor fields are copied into result, run, and execution-log snapshots.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, JdbcTemplate, PostgreSQL, Python 3/FastAPI/urllib, React 18, TypeScript, Ant Design, JUnit 5/Mockito, unittest.

---

### Task 1: Add execution-target metadata and safe catalog

**Files:**
- Create: `src/main/java/com/aitaskcenter/dto/ExecutionTargetItem.java`
- Modify: `src/main/java/com/aitaskcenter/dto/AiProviderConfigItem.java`
- Modify: `src/main/java/com/aitaskcenter/dto/LocalCliConfigItem.java`
- Modify: `src/main/java/com/aitaskcenter/service/AiConfigService.java`
- Modify: `src/main/java/com/aitaskcenter/controller/AiConfigController.java`
- Create: `src/test/java/com/aitaskcenter/service/AiConfigServiceExecutionTargetTest.java`

- [ ] **Step 1: Write failing catalog tests**

Add tests that persist an OpenAI text Provider, a MiMo TTS Provider, and a Codex CLI, then assert:

```java
List<ExecutionTargetItem> targets = service.getExecutionTargets();
assertEquals(List.of("AUDIO_TTS"), target("AI_PROVIDER", "xiaomi-mimo-tts", targets).capabilities());
assertEquals(List.of("TEXT_GENERATION", "CODE_EXECUTION"), target("CLI", "codex", targets).capabilities());
assertNull(target("AI_PROVIDER", "xiaomi-mimo-tts", targets).apiKey());
```

Also verify saving `mimo-tts` preserves `voice`, `capabilities`, `options`, and `enabled`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -Dtest=AiConfigServiceExecutionTargetTest test
```

Expected: compilation fails because `ExecutionTargetItem` and new Provider fields do not exist.

- [ ] **Step 3: Implement metadata DTOs and normalization**

`ExecutionTargetItem` exposes only:

```java
public record ExecutionTargetItem(
        String type,
        String id,
        String label,
        String protocol,
        List<String> capabilities,
        boolean enabled) {}
```

Extend Provider and CLI DTOs with typed capabilities. Provider additionally gets `voice`, `options`, and `enabled`. Normalize defaults:

```java
private static List<String> providerCapabilities(AiProviderConfigItem item) {
    if (item.getCapabilities() != null && !item.getCapabilities().isEmpty()) {
        return normalizeCapabilities(item.getCapabilities());
    }
    return "mimo-tts".equals(item.getType())
            ? List.of("AUDIO_TTS")
            : List.of("TEXT_GENERATION");
}
```

Add `GET /api/ai/execution-targets`. Never copy `apiKey` into its response.

- [ ] **Step 4: Run focused Java tests and verify GREEN**

Run:

```bash
mvn -Dtest=AiConfigServiceExecutionTargetTest test
```

Expected: all tests pass.

### Task 2: Add handler and executor snapshot fields

**Files:**
- Create: `src/main/java/com/aitaskcenter/service/TaskExecutionTargetResolver.java`
- Modify: `src/main/java/com/aitaskcenter/model/TaskConfig.java`
- Modify: `src/main/java/com/aitaskcenter/model/TaskResult.java`
- Modify: `src/main/java/com/aitaskcenter/model/TaskRun.java`
- Modify: `src/main/java/com/aitaskcenter/model/TaskExecutionLog.java`
- Create: `src/test/java/com/aitaskcenter/service/TaskExecutionTargetResolverTest.java`

- [ ] **Step 1: Write failing compatibility tests**

Test explicit fields and legacy inference:

```java
assertEquals(
        new Target("word_clean_best_sentence_tts", "AI_PROVIDER", "xiaomi-mimo-tts"),
        resolver.resolve(null, null, null, "codex", "[\"public.word_clean_best_sentence\"]", null));

assertEquals(
        new Target("word_clean_sentence_score", "CLI", "codex"),
        resolver.resolve(null, null, null, "codex", "[\"public.word_clean_sentence\"]", null));
```

Explicit `handlerKey`, `executorType`, and `executorId` must always win.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -Dtest=TaskExecutionTargetResolverTest test
```

Expected: compilation fails because resolver and snapshot fields are absent.

- [ ] **Step 3: Add nullable migration fields and resolver**

Add JPA fields with lengths and no non-null constraint during migration:

```java
private String handlerKey;
private String executorType;
private String executorId;
```

`TaskConfig` also gets `onboardingCliId`; `TaskExecutionLog` gets `executorLabel`.

Resolver rules:

```java
if (hasText(handlerKey) && hasText(executorType) && hasText(executorId)) return explicit;
if (payloadTaskType is TTS or selectedTables contains word_clean_best_sentence)
    return TTS + AI_PROVIDER + xiaomi-mimo-tts;
return SCORE + CLI + legacyCliId;
```

No migration SQL updates historical rows.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
mvn -Dtest=TaskExecutionTargetResolverTest test
```

Expected: all tests pass.

### Task 3: Validate task configuration and snapshot batch execution

**Files:**
- Modify: `src/main/java/com/aitaskcenter/service/TaskConfigService.java`
- Modify: `src/main/java/com/aitaskcenter/service/TaskRunService.java`
- Modify: `src/main/java/com/aitaskcenter/service/TaskResultService.java`
- Modify: `src/main/java/com/aitaskcenter/dto/StartTaskRunRequest.java`
- Modify: `src/main/java/com/aitaskcenter/dto/GenerateTaskRunBatchRequest.java`
- Modify: `src/test/java/com/aitaskcenter/service/TaskConfigServiceBatchTest.java`
- Modify: `src/test/java/com/aitaskcenter/service/TaskRunServiceListTest.java`
- Create: `src/test/java/com/aitaskcenter/service/TaskConfigExecutionTargetTest.java`

- [ ] **Step 1: Write failing service tests**

Cover these contracts:

```java
request.setCliId(null);
service.startExecution(request);
assertEquals("AI_PROVIDER", run.getExecutorType());
assertEquals("xiaomi-mimo-tts", run.getExecutorId());
```

```java
assertEquals("word_clean_best_sentence_tts", jdbcTemplate.insertArguments[5]);
assertEquals("AI_PROVIDER", jdbcTemplate.insertArguments[6]);
assertEquals("xiaomi-mimo-tts", jdbcTemplate.insertArguments[7]);
```

Saving a TTS handler with a target lacking `AUDIO_TTS` must fail with a clear message. Saving a scoring handler with either a `TEXT_GENERATION` CLI or Provider must pass.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -Dtest=TaskConfigServiceBatchTest,TaskRunServiceListTest,TaskConfigExecutionTargetTest test
```

Expected: assertions fail because services still require and overwrite `cliId`.

- [ ] **Step 3: Implement snapshot propagation and start semantics**

Changes:

- Task config saves an explicit handler and execution target; legacy `cliId` becomes the onboarding fallback.
- Batch insert adds `handler_key`, `executor_type`, and `executor_id` columns.
- New task runs copy all four fields from task configuration.
- Start request no longer requires a CLI and never overwrites the run target.
- Execution logs copy handler/target snapshot and use `executorLabel`; legacy `cliId` remains populated only for compatibility.
- Single result execution resolves target from result, task config, then legacy fields.

Do not modify existing result/run rows as part of migration.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
mvn -Dtest=TaskConfigServiceBatchTest,TaskRunServiceListTest,TaskConfigExecutionTargetTest test
```

Expected: all tests pass.

### Task 4: Resolve execution targets in Python Worker

**Files:**
- Modify: `python-worker/app/main.py`
- Create: `python-worker/tests/test_execution_targets.py`
- Modify: `python-worker/tests/test_mimo_tts_execution.py`
- Modify: `python-worker/tests/test_tts_batch_execution.py`

- [ ] **Step 1: Write failing Python target-resolution tests**

Add snapshots with new optional fields and assert:

```python
target = worker.resolve_execution_target("AI_PROVIDER", "xiaomi-mimo-tts", "AUDIO_TTS")
self.assertEqual("mimo-tts", target.protocol)
self.assertNotIn("database-secret", str(target.public_metadata()))
```

```python
with self.assertRaises(worker.HTTPException):
    worker.resolve_execution_target("CLI", "codex", "AUDIO_TTS")
```

Change MiMo test to call `load_mimo_tts_config("xiaomi-mimo-tts")` and verify the requested Provider ID is used instead of a global hard-coded ID.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
python-worker/.venv/bin/python -m unittest python-worker/tests/test_execution_targets.py python-worker/tests/test_mimo_tts_execution.py -v
```

Expected: errors because the target resolver and new function signatures are absent.

- [ ] **Step 3: Implement target dataclass and DB resolution**

Add:

```python
@dataclass(frozen=True)
class ExecutionTarget:
    executor_type: str
    executor_id: str
    label: str
    protocol: str
    capabilities: tuple[str, ...]
    config: dict[str, Any]
```

`resolve_execution_target` loads exactly one CLI or Provider from `tb_ai_config`, infers legacy MiMo metadata, validates enabled/capability, and never exposes the API key in response metadata.

`load_mimo_tts_config(provider_id)` and `generate_mimo_tts(tts_input, provider_id)` use the snapshotted ID.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
python-worker/.venv/bin/python -m unittest python-worker/tests/test_execution_targets.py python-worker/tests/test_mimo_tts_execution.py python-worker/tests/test_tts_batch_execution.py -v
```

Expected: all tests pass.

### Task 5: Normalize CLI and direct AI text generation

**Files:**
- Modify: `python-worker/app/main.py`
- Create: `python-worker/tests/test_text_generation_executors.py`
- Modify: `python-worker/tests/test_validation_execution.py`

- [ ] **Step 1: Write failing adapter tests**

Test normalized responses for CLI, OpenAI-compatible, and Anthropic-compatible targets. For OpenAI-compatible:

```python
result = worker.execute_text_generation(target, '{"task":"score"}')
self.assertEqual('{"items":[]}', result["rawOutput"])
self.assertEqual("AI_PROVIDER", result["executorType"])
self.assertEqual("openai-compatible", result["protocol"])
self.assertNotIn("secret", json.dumps(result))
```

HTTP requests are mocked and assert the correct URL, auth header, model, and prompt body.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
python-worker/.venv/bin/python -m unittest python-worker/tests/test_text_generation_executors.py -v
```

Expected: `execute_text_generation` is missing.

- [ ] **Step 3: Implement normalized adapters**

CLI delegates to existing `run_cli_prompt`. OpenAI-compatible sends `POST <base_url>/chat/completions`; Anthropic-compatible sends `POST <base_url>/messages`. Both normalize output without returning secrets:

```python
return {
    "rawOutput": raw_output,
    "executorType": target.executor_type,
    "executorId": target.executor_id,
    "protocol": target.protocol,
    "model": model,
    "metadata": safe_metadata,
}
```

Scoring handlers call this function and continue using the existing JSON parsing and business validation.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
python-worker/.venv/bin/python -m unittest python-worker/tests/test_text_generation_executors.py python-worker/tests/test_validation_execution.py -v
```

Expected: all tests pass.

### Task 6: Route by handler snapshot with legacy fallback

**Files:**
- Modify: `python-worker/app/main.py`
- Modify: `python-worker/tests/test_tts_batch_execution.py`
- Create: `python-worker/tests/test_handler_routing.py`

- [ ] **Step 1: Write failing routing tests**

Explicit handler wins over payload:

```python
run = snapshot(handler_key=worker.HANDLER_TTS, executor_type="AI_PROVIDER", executor_id="xiaomi-mimo-tts")
worker.process_task_run_batch_by_type(run.id)
process_tts.assert_called_once()
```

Legacy runs with empty handler still route by payload. An unsupported handler fails before any CLI or API call.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
python-worker/.venv/bin/python -m unittest python-worker/tests/test_handler_routing.py -v
```

Expected: snapshots do not yet contain handler/target fields.

- [ ] **Step 3: Extend SQL snapshots and dispatcher**

Select new nullable columns from task result and task run. Resolve effective fields with explicit snapshot first and legacy payload second. TTS passes `executorId` to `generate_mimo_tts`; scoring passes the resolved target to `execute_text_generation`.

Queue claim includes handler/target snapshot metadata or reloads it before execution, but never accepts a caller-selected CLI override.

- [ ] **Step 4: Run all Python tests**

Run:

```bash
python-worker/.venv/bin/python -m unittest discover -s python-worker/tests -p 'test_*.py' -v
```

Expected: all tests pass.

### Task 7: Update React task configuration and task list

**Files:**
- Modify: `web-react/src/api.ts`
- Modify: `web-react/src/App.tsx`
- Modify: `web-react/src/styles.css`

- [ ] **Step 1: Add TypeScript execution-target types and API**

Add:

```ts
export type ExecutorType = 'CLI' | 'AI_PROVIDER';
export type HandlerKey = 'word_clean_sentence_score' | 'word_clean_best_sentence_tts';
export interface ExecutionTargetItem {
  type: ExecutorType;
  id: string;
  label: string;
  protocol: string;
  capabilities: string[];
  enabled: boolean;
}
```

Extend `TaskConfig`, `TaskResult`, and `TaskRun` with handler/target fields, and add `getExecutionTargets()`.

- [ ] **Step 2: Change task form and filtered target selection**

The task form selects a handler, then a grouped runtime target filtered by required capability. It separately shows an onboarding CLI. Existing task rows use compatibility display when new fields are empty.

- [ ] **Step 3: Change task list, run list, and start modal**

- Rename “执行工具” to “调用通道”.
- Display `AI API · 小米 MiMo TTS` or `本地 CLI · Codex`.
- Replace CLI filter with type/target filter.
- Remove required CLI selection from “开始执行”; retain concurrency only.
- Replace “多线程（独立 CLI 子进程）” with “Python Worker 并发线程”.
- Keep onboarding drawer CLI selection labeled “接入 CLI”.

- [ ] **Step 4: Build React application**

Run:

```bash
npm --prefix web-react run build
```

Expected: TypeScript and Vite build succeed.

### Task 8: Full regression and local service verification

**Files:**
- Modify: `docs/ai-task-center-project-overview.md`
- Modify: `docs/ai-task-center-rob-english-word-task-rules.md`
- Modify: `task_plan.md`
- Modify: `findings.md`
- Modify: `progress.md`

- [ ] **Step 1: Update architecture documentation**

Document scheduler/handler/target separation, new task fields, compatibility behavior, and the rule that all outbound CLI/API/TTS calls happen in Python Worker.

- [ ] **Step 2: Run Java tests**

Run:

```bash
mvn test
```

Expected: zero failures and zero errors. If Mockito cannot attach inside the sandbox, rerun the same command with the required host permission and record the reason.

- [ ] **Step 3: Run Python tests and syntax checks**

Run:

```bash
python-worker/.venv/bin/python -m unittest discover -s python-worker/tests -p 'test_*.py' -v
python-worker/.venv/bin/python -m py_compile python-worker/app/main.py
bash -n scripts/start-dev.sh
```

Expected: all tests and syntax checks pass.

- [ ] **Step 4: Build frontend**

Run:

```bash
npm --prefix web-react run build
```

Expected: build succeeds.

- [ ] **Step 5: Start and verify services**

Read `docs/ai-task-center-runtime-guide.md`, run `./scripts/start-dev.sh`, then verify:

```text
http://127.0.0.1:19637/
http://127.0.0.1:18743/api/task-run/list
http://127.0.0.1:19186/api/health
```

Verify safely without executing a real task or calling MiMo:

- Execution target API returns MiMo as `AI_PROVIDER` with `AUDIO_TTS` and no key.
- Existing TTS rows display MiMo through compatibility resolution.
- Start modal does not require CLI.
- Existing score rows still resolve to Codex CLI.

- [ ] **Step 6: Review diff and report**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only scoped project files and pre-existing user changes remain.
