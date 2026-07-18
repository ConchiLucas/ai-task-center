# New Task Onboarding Runtime Target Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make new tasks start with only business/data-source information, select one model runtime target during onboarding, and route arbitrary task-specific behavior through a strict Python Worker handler registry.

**Architecture:** `TaskConfig` starts at `TARGET_SELECTION` with no handler or runtime target. Onboarding stores one safe execution target, assigns `task_config_<id>`, and accepts `CODE_READY` only after Java verifies the Python Worker registry entry and target capability. Runtime paths dispatch strictly through handler and target snapshots, never through tables, payload types, or legacy CLI fields.

**Tech Stack:** Java 21, Spring Boot 3, PostgreSQL, Python 3.13, FastAPI, pytest, React, TypeScript, Ant Design, Vite.

---

## File responsibility map

- `TaskConfig.java` and `TaskConfigService.java`: basic-only task creation and nullable runtime metadata.
- `OnboardingStep.java` and `TaskOnboardingService.java`: target selection and eight-step workflow.
- `TaskOnboardingPromptBuilder.java`: generic safe prompt for any external coding tool.
- `PythonWorkerClient.java`: handler catalog and task-specific batch prompt calls.
- `python-worker/app/handler_registry.py`: handler definitions, registration, discovery, and phase validation.
- `python-worker/app/main.py`: built-in registrations and strict registry-backed dispatch.
- `web-react/src/App.tsx`: basic-only task form.
- `web-react/src/TaskOnboardingDrawer.tsx`: target selection and generic prompt flow.

### Task 1: New task lifecycle without fixed handler or CLI

**Files:**
- Modify: `src/main/java/com/aitaskcenter/model/TaskConfig.java`
- Modify: `src/main/java/com/aitaskcenter/service/TaskConfigService.java`
- Modify: `src/main/java/com/aitaskcenter/service/onboarding/OnboardingStep.java`
- Create: `src/main/java/com/aitaskcenter/config/LegacyCliColumnInitializer.java`
- Test: `src/test/java/com/aitaskcenter/service/TaskConfigNewLifecycleTest.java`

- [ ] **Step 1: Write failing lifecycle tests**

```java
@Test
void createsBasicTaskAwaitingTargetSelection() {
    TaskConfig created = service.create(basicTaskInput());
    assertNull(created.getHandlerKey());
    assertNull(created.getExecutorType());
    assertNull(created.getExecutorId());
    assertEquals("TARGET_SELECTION", created.getOnboardingStep());
}

@Test
void updatingBasicFieldsPreservesRegisteredRuntimeMetadata() {
    existing.setHandlerKey("task_config_42");
    existing.setExecutorType("CLI");
    existing.setExecutorId("codex");
    TaskConfig updated = service.update(42L, changedBasicInput());
    assertEquals("task_config_42", updated.getHandlerKey());
    assertEquals("codex", updated.getExecutorId());
}
```

- [ ] **Step 2: Run tests and verify RED**

```bash
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" \
  -q -Dtest=TaskConfigNewLifecycleTest test
```

Expected: creation still requires fixed handler/CLI metadata and `TARGET_SELECTION` is absent.

- [ ] **Step 3: Implement the minimal lifecycle**

```java
public enum OnboardingStep {
    TARGET_SELECTION, RESULT_CODE, RESULT_VALIDATION, RESULT_GENERATION,
    BATCH_CODE, BATCH_VALIDATION, BATCH_GENERATION, READY
}
```

`create` copies only basic fields, writes `null` to transitional `cliId/onboardingCliId`, clears handler/target, and initializes `TARGET_SELECTION`; `update` copies only basic fields and preserves runtime metadata. Keep the legacy accessors temporarily so intermediate commits compile; Task 8 removes their mappings after all consumers are migrated. Add an `ApplicationRunner` which idempotently executes:

```java
List.of(
    "alter table if exists tb_task_config alter column cli_id drop not null",
    "alter table if exists tb_task_result alter column cli_id drop not null",
    "alter table if exists tb_task_run alter column cli_id drop not null",
    "alter table if exists tb_task_execution_log alter column cli_id drop not null"
).forEach(jdbcTemplate::execute);
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aitaskcenter/model/TaskConfig.java \
  src/main/java/com/aitaskcenter/service/TaskConfigService.java \
  src/main/java/com/aitaskcenter/service/onboarding/OnboardingStep.java \
  src/main/java/com/aitaskcenter/config/LegacyCliColumnInitializer.java \
  src/test/java/com/aitaskcenter/service/TaskConfigNewLifecycleTest.java
git commit -m "refactor: start new tasks without execution metadata"
```

### Task 2: Onboarding model target selection

**Files:**
- Create: `src/main/java/com/aitaskcenter/dto/SelectExecutionTargetRequest.java`
- Modify: `src/main/java/com/aitaskcenter/dto/TaskOnboardingTaskSummary.java`
- Modify: `src/main/java/com/aitaskcenter/controller/TaskOnboardingController.java`
- Modify: `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingService.java`
- Test: `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingTargetSelectionTest.java`

- [ ] **Step 1: Write failing target-selection tests**

```java
@Test
void selectsEnabledTargetAndAdvancesToResultCode() {
    when(aiConfigService.getExecutionTargets()).thenReturn(List.of(mimoTarget(true)));
    TaskOnboardingResponse response = service.selectExecutionTarget(
        42L, new SelectExecutionTargetRequest("AI_PROVIDER", "xiaomi-mimo-tts"));
    assertEquals("AI_PROVIDER", task.getExecutorType());
    assertEquals("xiaomi-mimo-tts", task.getExecutorId());
    assertEquals("RESULT_CODE", response.getCurrentStep());
}

@Test
void unknownOrDisabledTargetDoesNotAdvance() {
    assertThrows(IllegalArgumentException.class,
        () -> service.selectExecutionTarget(42L, new SelectExecutionTargetRequest("CLI", "missing")));
    assertEquals("TARGET_SELECTION", task.getOnboardingStep());
}

@Test
void changingTargetAfterCodeReadyResetsResultCodeState() {
    task.setOnboardingStep("RESULT_VALIDATION");
    task.setHandlerKey("task_config_42");
    service.selectExecutionTarget(42L, request("CLI", "codex"));
    assertNull(task.getHandlerKey());
    assertEquals("RESULT_CODE", task.getOnboardingStep());
    assertNotEquals("old-token", context(task).get("resultCodeToken"));
}
```

- [ ] **Step 2: Run tests and verify RED**

```bash
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" \
  -q -Dtest=TaskOnboardingTargetSelectionTest test
```

Expected: request DTO and selection method are missing.

- [ ] **Step 3: Implement request, endpoint, and transition**

```java
public record SelectExecutionTargetRequest(String executorType, String executorId) {}
```

Add `POST /api/task/{id}/onboarding/execution-target`. The service finds an exact enabled `ExecutionTargetItem`, saves only `executorType/executorId`, clears `handlerKey`, clears downstream code-ready markers, rotates the result token, and moves to `RESULT_CODE`. Re-selecting the same target during `RESULT_CODE` is idempotent; changing a target after code readiness requires explicit UI confirmation and resets to `RESULT_CODE`. Update `TaskOnboardingTaskSummary` to expose handler and target fields but no CLI fields.

- [ ] **Step 4: Run Step 2 and verify GREEN**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aitaskcenter/dto/SelectExecutionTargetRequest.java \
  src/main/java/com/aitaskcenter/dto/TaskOnboardingTaskSummary.java \
  src/main/java/com/aitaskcenter/controller/TaskOnboardingController.java \
  src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingService.java \
  src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingTargetSelectionTest.java
git commit -m "feat: select model target during onboarding"
```

### Task 3: Python Worker handler registry and safe catalog

**Files:**
- Create: `python-worker/app/handler_registry.py`
- Modify: `python-worker/app/main.py`
- Create: `python-worker/tests/test_task_handler_registry.py`
- Create: `python-worker/tests/test_task_handler_catalog.py`

- [ ] **Step 1: Write failing registry tests**

```python
def test_descriptor_is_safe_and_phase_flags_are_derived():
    registry = TaskHandlerRegistry()
    registry.register(TaskHandlerDefinition(
        handler_key="task_config_42",
        required_capability="TEXT_GENERATION",
        result_generator=lambda *args: {},
        single_processor=lambda *args: {},
        batch_prompt_builder=lambda *args: {},
        batch_processor=lambda *args: {},
    ))
    assert registry.describe("task_config_42") == {
        "handlerKey": "task_config_42",
        "requiredCapability": "TEXT_GENERATION",
        "supportsResultGeneration": True,
        "supportsSingleValidation": True,
        "supportsBatchBuild": True,
        "supportsBatchExecution": True,
    }

def test_catalog_never_serializes_callbacks_or_secrets(client):
    text = json.dumps(client.get("/api/task-handlers").json()).lower()
    assert "api_key" not in text
    assert "<function" not in text
```

- [ ] **Step 2: Run tests and verify RED**

```bash
cd python-worker
.venv/bin/python -m pytest -q tests/test_task_handler_registry.py tests/test_task_handler_catalog.py
```

Expected: registry module and endpoints do not exist.

- [ ] **Step 3: Implement the registry**

```python
@dataclass(frozen=True)
class TaskHandlerDefinition:
    handler_key: str
    required_capability: str
    result_generator: Callable[..., dict[str, Any]] | None = None
    single_processor: Callable[..., dict[str, Any]] | None = None
    batch_prompt_builder: Callable[..., dict[str, Any]] | None = None
    batch_processor: Callable[..., dict[str, Any]] | None = None

class TaskHandlerRegistry:
    def __init__(self) -> None:
        self._handlers: dict[str, TaskHandlerDefinition] = {}

    def register(self, definition: TaskHandlerDefinition) -> None:
        key = definition.handler_key.strip()
        capability = definition.required_capability.strip().upper()
        if not key or capability not in {"TEXT_GENERATION", "AUDIO_TTS"}:
            raise ValueError("任务处理器 Key 或能力无效")
        if key in self._handlers:
            raise ValueError(f"任务处理器重复注册: {key}")
        self._handlers[key] = replace(
            definition, handler_key=key, required_capability=capability)

    def require(self, handler_key: str) -> TaskHandlerDefinition:
        key = str(handler_key or "").strip()
        if not key or key not in self._handlers:
            raise ValueError(f"任务处理器未注册: {key or '空'}")
        return self._handlers[key]

    def describe(self, handler_key: str) -> dict[str, Any]:
        item = self.require(handler_key)
        return handler_descriptor(item)

    def list_descriptors(self) -> list[dict[str, Any]]:
        return [handler_descriptor(self._handlers[key]) for key in sorted(self._handlers)]
```

Reject blank/duplicate keys and unknown capability names. Add safe `GET /api/task-handlers` and `GET /api/task-handlers/{handler_key}` endpoints. Register existing score and TTS callbacks after their functions are defined.

- [ ] **Step 4: Run Step 2 and verify GREEN**

- [ ] **Step 5: Commit**

```bash
git add python-worker/app/handler_registry.py python-worker/app/main.py \
  python-worker/tests/test_task_handler_registry.py python-worker/tests/test_task_handler_catalog.py
git commit -m "feat: add strict task handler registry"
```

### Task 4: Strict registry dispatch

**Files:**
- Modify: `python-worker/app/main.py`
- Modify: `python-worker/tests/test_handler_routing.py`
- Create: `python-worker/tests/test_strict_handler_dispatch.py`

- [ ] **Step 1: Write failing strict-dispatch tests**

```python
def test_result_generation_uses_registered_handler(monkeypatch):
    monkeypatch.setattr(worker, "load_task_config_snapshot", lambda _: custom_task_snapshot())
    callback = Mock(return_value={"insertedCount": 2})
    register_custom_handler("task_config_42", result_generator=callback)
    assert worker.generate_results_for_task_config(42, False)["insertedCount"] == 2
    callback.assert_called_once_with(42, False, worker.RECORD_TYPE_FORMAL, None)

@pytest.mark.parametrize("handler_key", ["", "missing"])
def test_missing_handler_never_falls_back_to_payload_or_tables(handler_key, monkeypatch):
    monkeypatch.setattr(worker, "load_task_run_snapshot", lambda _: run_snapshot(handler_key))
    with pytest.raises(worker.HTTPException, match="处理器"):
        worker.process_task_run_batch_by_type(8)
```

- [ ] **Step 2: Run tests and verify RED**

```bash
cd python-worker
.venv/bin/python -m pytest -q tests/test_handler_routing.py tests/test_strict_handler_dispatch.py
```

Expected: table/payload/legacy CLI inference still makes tests fail.

- [ ] **Step 3: Replace inference with strict registry lookup**

```python
def generate_results_for_task_config(task_config_id, overwrite, record_type=RECORD_TYPE_FORMAL, limit=None):
    task = load_task_config_snapshot(task_config_id)
    handler = TASK_HANDLER_REGISTRY.require(require_handler_key(task.handler_key))
    require_execution_snapshot(task.executor_type, task.executor_id)
    require_handler_phase(handler, "result_generation")
    return handler.result_generator(task_config_id, overwrite, record_type, limit)
```

Apply the same pattern to single-result and batch execution. Remove selected-table mode detection, payload `taskType` routing, legacy CLI target fallback, and SQL `coalesce` fallback to task configuration.

- [ ] **Step 4: Run focused and full Python tests**

```bash
cd python-worker
.venv/bin/python -m pytest -q tests/test_handler_routing.py tests/test_strict_handler_dispatch.py
.venv/bin/python -m pytest -q
```

Expected: both commands pass.

- [ ] **Step 5: Commit**

```bash
git add python-worker/app/main.py python-worker/tests
git commit -m "refactor: route tasks strictly through registered handlers"
```

### Task 5: Verify handler readiness during CODE_READY

**Files:**
- Create: `src/main/java/com/aitaskcenter/dto/TaskHandlerDescriptor.java`
- Modify: `src/main/java/com/aitaskcenter/service/PythonWorkerClient.java`
- Modify: `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingService.java`
- Modify: `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilder.java`
- Test: `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingHandlerReadinessTest.java`
- Test: `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilderTest.java`

- [ ] **Step 1: Write failing readiness tests**

```java
@Test
void resultCodeReadyRequiresRegisteredCompatibleHandler() {
    when(workerClient.getTaskHandler("task_config_42")).thenReturn(
        new TaskHandlerDescriptor("task_config_42", "TEXT_GENERATION", true, true, false, false));
    when(aiConfigService.getExecutionTargets()).thenReturn(List.of(codexTextTarget()));
    service.report(42L, codeReady("result", "token-1"));
    assertEquals("task_config_42", task.getHandlerKey());
    assertEquals("RESULT_VALIDATION", task.getOnboardingStep());
}

@Test
void capabilityMismatchDoesNotAdvance() {
    when(workerClient.getTaskHandler("task_config_42")).thenReturn(audioHandler());
    assertThrows(IllegalArgumentException.class,
        () -> service.report(42L, codeReady("result", "token-1")));
    assertEquals("RESULT_CODE", task.getOnboardingStep());
}
```

- [ ] **Step 2: Run tests and verify RED**

```bash
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" \
  -q -Dtest=TaskOnboardingHandlerReadinessTest,TaskOnboardingPromptBuilderTest test
```

Expected: handler catalog client and readiness gate are missing.

- [ ] **Step 3: Implement catalog client and readiness gate**

```java
public record TaskHandlerDescriptor(
    String handlerKey,
    String requiredCapability,
    boolean supportsResultGeneration,
    boolean supportsSingleValidation,
    boolean supportsBatchBuild,
    boolean supportsBatchExecution) {}
```

Add `PythonWorkerClient.getTaskHandler(String)`. On result `CODE_READY`, require result generation and single validation; on batch `CODE_READY`, require batch build and batch execution. Check the selected safe `ExecutionTargetItem.capabilities()` contains `requiredCapability`; set `handlerKey` only after result-stage validation succeeds.

Change prompts to include `task_config_<id>`, target type/id/protocol/capabilities, and “任意外部编码工具”; assert prompts do not contain `api_key`.

- [ ] **Step 4: Run Step 2 and verify GREEN**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aitaskcenter/dto/TaskHandlerDescriptor.java \
  src/main/java/com/aitaskcenter/service/PythonWorkerClient.java \
  src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingService.java \
  src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilder.java \
  src/test/java/com/aitaskcenter/service/onboarding
git commit -m "feat: verify task handlers during onboarding"
```

### Task 6: Build batch prompts through the registered handler

**Files:**
- Modify: `python-worker/app/handler_registry.py`
- Modify: `python-worker/app/main.py`
- Modify: `src/main/java/com/aitaskcenter/service/PythonWorkerClient.java`
- Modify: `src/main/java/com/aitaskcenter/service/TaskConfigService.java`
- Modify: `src/main/java/com/aitaskcenter/dto/GenerateTaskRunBatchRequest.java`
- Test: `python-worker/tests/test_handler_batch_prompt.py`
- Test: `src/test/java/com/aitaskcenter/service/TaskConfigHandlerBatchTest.java`

- [ ] **Step 1: Write failing batch-prompt tests**

```python
def test_batch_prompt_calls_registered_handler(client, custom_handler):
    response = client.post("/api/task-handlers/task_config_42/batch-prompt", json={
        "taskConfigId": 42,
        "taskRunName": "任务 42 - 批次 1",
        "taskResultIds": [101, 102],
    })
    assert response.status_code == 200
    assert response.json()["_meta"]["handlerKey"] == "task_config_42"
```

```java
@Test
void generatedBatchUsesHandlerPromptAndExactTargetSnapshot() {
    when(workerClient.buildBatchPrompt("task_config_42", 42L, "任务 - 批次 1", List.of(101L)))
        .thenReturn("{\"_meta\":{\"handlerKey\":\"task_config_42\"}}");
    service.generateRunBatches(42L, requestWithoutCli());
    verifyInsertedRun("task_config_42", "CLI", "codex");
}
```

- [ ] **Step 2: Run tests and verify RED**

```bash
cd python-worker
.venv/bin/python -m pytest -q tests/test_handler_batch_prompt.py
cd ..
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" \
  -q -Dtest=TaskConfigHandlerBatchTest test
```

Expected: Worker endpoint/client method are missing and Java still accepts CLI override.

- [ ] **Step 3: Implement the batch-prompt callback path**

Add a Pydantic request containing `taskConfigId`, `taskRunName`, and ordered `taskResultIds`. Require the registry entry and its `batch_prompt_builder`. Add `PythonWorkerClient.buildBatchPrompt(String handlerKey, Long taskConfigId, String taskRunName, List<Long> taskResultIds)`, remove `cliId` from `GenerateTaskRunBatchRequest`, and have Java snapshot only the task's handler/target. Retained legacy `cli_id` columns receive `null`.

- [ ] **Step 4: Run Step 2 and verify GREEN**

- [ ] **Step 5: Commit**

```bash
git add python-worker/app python-worker/tests/test_handler_batch_prompt.py \
  src/main/java/com/aitaskcenter/service/PythonWorkerClient.java \
  src/main/java/com/aitaskcenter/service/TaskConfigService.java \
  src/main/java/com/aitaskcenter/dto/GenerateTaskRunBatchRequest.java \
  src/test/java/com/aitaskcenter/service/TaskConfigHandlerBatchTest.java
git commit -m "feat: build batches through task handler registry"
```

### Task 7: React basic task form and onboarding target UI

**Files:**
- Modify: `web-react/src/api.ts`
- Modify: `web-react/src/App.tsx`
- Modify: `web-react/src/TaskOnboardingDrawer.tsx`
- Modify: `web-react/src/App.css`

- [ ] **Step 1: Make TypeScript types describe only the new flow**

```ts
export interface TaskConfig {
  ID?: number;
  taskName: string;
  projectId: number;
  handlerKey?: string;
  executorType?: ExecutorType;
  executorId?: string;
  databaseConfigId?: number | null;
  selectedTables?: string;
  taskDesc?: string;
}

export type TaskOnboardingStep =
  | 'TARGET_SELECTION' | 'RESULT_CODE' | 'RESULT_VALIDATION'
  | 'RESULT_GENERATION' | 'BATCH_CODE' | 'BATCH_VALIDATION'
  | 'BATCH_GENERATION' | 'READY';
```

Add `selectTaskOnboardingExecutionTarget(id, {executorType, executorId})` and remove CLI fields from batch-generation request types.

- [ ] **Step 2: Run build and verify RED**

```bash
cd web-react
npm run build
```

Expected: TypeScript identifies remaining task `cliId/onboardingCliId`, fixed handler union, and batch CLI form references.

- [ ] **Step 3: Implement the UI**

The create/update payload is exactly:

```ts
const payload = {
  taskName: values.taskName,
  projectId: values.projectId,
  databaseConfigId: values.databaseConfigId || null,
  selectedTables: editingTask?.selectedTables || '',
  taskDesc: values.taskDesc || '',
};
```

Remove handler/runtime/onboarding CLI controls from `App.tsx`. In `TaskOnboardingDrawer`, render `TARGET_SELECTION` with the safe target list, protocol, and capability tags; replace `cliConfigs` prop with `executionTargets`. After a target is selected, show it in the summary with a “重新选择” action that warns about resetting to `RESULT_CODE`. Prompt copy text must reference any external coding tool. Remove validation/formal batch CLI selectors. Make handler keys generic strings.

- [ ] **Step 4: Build and visually verify**

```bash
cd web-react
npm run build
```

Expected: build passes. Browser acceptance checks basic-only create form, active target-selection node, target advance, generic prompt text, and no batch CLI selector.

- [ ] **Step 5: Commit**

```bash
git add web-react/src/api.ts web-react/src/App.tsx \
  web-react/src/TaskOnboardingDrawer.tsx web-react/src/App.css
git commit -m "feat: move model target selection into onboarding"
```

### Task 8: Remove legacy CLI runtime identity

**Files:**
- Modify: `src/main/java/com/aitaskcenter/model/TaskResult.java`
- Modify: `src/main/java/com/aitaskcenter/model/TaskRun.java`
- Modify: `src/main/java/com/aitaskcenter/model/TaskExecutionLog.java`
- Modify: `src/main/java/com/aitaskcenter/dto/StartTaskRunRequest.java`
- Modify: `src/main/java/com/aitaskcenter/service/TaskResultService.java`
- Modify: `src/main/java/com/aitaskcenter/service/TaskRunService.java`
- Modify: `src/main/java/com/aitaskcenter/service/TaskExecutionTargetResolver.java`
- Modify: `python-worker/app/main.py`
- Test: `src/test/java/com/aitaskcenter/service/StrictExecutionSnapshotTest.java`
- Test: `python-worker/tests/test_strict_execution_snapshots.py`

- [ ] **Step 1: Write failing strict snapshot tests**

```java
@Test
void incompleteSnapshotIsRejectedInsteadOfInferred() {
    assertThrows(IllegalArgumentException.class,
        () -> resolver.require("", "CLI", "codex"));
}
```

```python
def test_queue_does_not_pass_legacy_cli(monkeypatch):
    process = Mock(return_value={"successCount": 1, "failedCount": 0})
    monkeypatch.setattr(worker, "process_task_run_batch_by_type", process)
    worker.execute_queued_task(claim(cli_id="legacy"))
    process.assert_called_once_with(31)
```

- [ ] **Step 2: Run tests and verify RED**

```bash
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" \
  -q -Dtest=StrictExecutionSnapshotTest test
cd python-worker
.venv/bin/python -m pytest -q tests/test_strict_execution_snapshots.py
```

Expected: Java and queue still propagate/infer legacy CLI identity.

- [ ] **Step 3: Remove legacy mappings and fallbacks**

Replace the Java resolver API with:

```java
public ResolvedTarget require(String handlerKey, String executorType, String executorId) {
    if (!StringUtils.hasText(handlerKey)) throw new IllegalArgumentException("任务处理器未注册");
    if (!StringUtils.hasText(executorType) || !StringUtils.hasText(executorId)) {
        throw new IllegalArgumentException("任务未配置模型调用通道");
    }
    return new ResolvedTarget(
        handlerKey.trim(), executorType.trim().toUpperCase(Locale.ROOT), executorId.trim());
}
```

Remove legacy CLI JPA/API mappings, filters, start overrides, Worker claim fields, endpoint parameters, and queue arguments. Result/run/log creation writes only `handlerKey/executorType/executorId` snapshots.

- [ ] **Step 4: Run focused and full Java/Python tests**

```bash
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q test
cd python-worker
.venv/bin/python -m pytest -q
```

Expected: both full suites pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java python-worker/app/main.py python-worker/tests
git commit -m "refactor: remove legacy CLI execution identity"
```

### Task 9: Documentation and full runtime acceptance

**Files:**
- Modify: `docs/ai-task-center-project-overview.md`
- Modify: `docs/ai-task-center-rob-english-word-task-rules.md`
- Modify: `progress.md`
- Modify: `findings.md`

- [ ] **Step 1: Update documentation**

Document this exact lifecycle:

```text
新建基础任务 → 接入时选择唯一模型通道 → 外部工具实现 task_config_<id>
→ Worker 注册校验 → 结果验证 → 批次验证 → READY → 严格快照执行
```

State that coding tools are external and untracked, and old-task compatibility is not guaranteed.

- [ ] **Step 2: Run full verification**

```bash
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q test
cd python-worker
.venv/bin/python -m pytest -q
.venv/bin/python -m py_compile app/main.py app/handler_registry.py
cd ../web-react
npm run build
cd ..
bash -n scripts/start-dev.sh
git diff --check
```

Expected: all commands exit zero. FastAPI lifespan and Vite chunk-size warnings may remain; no test, compile, or formatting failures may remain.

- [ ] **Step 3: Restart services without executing tasks**

```bash
launchctl remove com.conchi.ai-task-center.backend || true
launchctl remove com.conchi.ai-task-center.python-worker || true
launchctl remove com.conchi.ai-task-center.frontend || true
./scripts/start-dev.sh
```

Do not click task execution, call model endpoints, or generate real validation/formal data.

- [ ] **Step 4: Perform read-only runtime checks**

```bash
curl -fsS http://127.0.0.1:18743/api/ai/execution-targets
curl -fsS http://127.0.0.1:19186/api/task-handlers
curl -fsS http://127.0.0.1:19186/api/health
curl -fsS http://127.0.0.1:19186/api/queue/status
curl -fsS -o /tmp/ai-task-center-frontend.check -w '%{http_code}\n' http://127.0.0.1:19637/
```

Expected: catalogs contain only safe metadata; Worker/DB are UP; queue counts are zero; frontend returns 200.

- [ ] **Step 5: Commit documentation**

```bash
git add docs/ai-task-center-project-overview.md \
  docs/ai-task-center-rob-english-word-task-rules.md progress.md findings.md
git commit -m "docs: document generic task onboarding lifecycle"
```
