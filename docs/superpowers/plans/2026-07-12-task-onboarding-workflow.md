# Task Onboarding Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace direct result/batch actions with a seven-step gated task-onboarding drawer that coordinates Codex callbacks, validates small real-table samples, safely cleans validation rows, and unlocks formal task generation.

**Architecture:** Persist the current onboarding step, status, and JSON context on `TaskConfig`. A focused `TaskOnboardingService` owns state transitions and read models; a separate `TaskOnboardingCleanupService` owns exact-marker deletion before delegating to existing result and batch generation services. The React app delegates the workflow UI to a standalone drawer component and treats the backend response as the only source of truth.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring Data JPA, JdbcTemplate, Jackson, JUnit 5, Mockito, React 18, TypeScript, Ant Design, Axios.

---

## File Structure

### Backend

- Modify `pom.xml`: add Spring Boot test dependencies.
- Modify `src/main/java/com/aitaskcenter/model/TaskConfig.java`: persist onboarding step, status, and JSON context.
- Create `src/main/java/com/aitaskcenter/service/onboarding/OnboardingStep.java`: canonical seven-step enum.
- Create `src/main/java/com/aitaskcenter/service/onboarding/OnboardingStatus.java`: canonical node status enum.
- Create `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingContext.java`: typed JSON state for tokens, markers, artifacts, entity IDs, and errors.
- Create `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilder.java`: result and batch Codex prompts.
- Create `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingCleanupService.java`: exact-marker cleanup transactions.
- Create `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingService.java`: read model, callback validation, confirmations, and generation gates.
- Create `src/main/java/com/aitaskcenter/controller/TaskOnboardingController.java`: onboarding HTTP API.
- Create `src/main/java/com/aitaskcenter/dto/TaskOnboardingReportRequest.java`: Codex callback payload.
- Create `src/main/java/com/aitaskcenter/dto/TaskOnboardingResponse.java`: drawer read model.
- Create `src/main/java/com/aitaskcenter/dto/TaskOnboardingNodeResponse.java`: one visible flow node.
- Modify `src/main/java/com/aitaskcenter/repository/TaskResultRepository.java`: exact result-marker lookups.
- Modify `src/main/java/com/aitaskcenter/repository/TaskRunRepository.java`: exact batch-marker lookups.
- Modify `src/main/java/com/aitaskcenter/repository/TaskRunResultRepository.java`: relationship validation queries.
- Modify `src/main/java/com/aitaskcenter/service/TaskConfigService.java`: reset onboarding when semantic configuration changes; expose formal generation to the gated service.
- Create `scripts/task-workflow`: stable local Codex callback command.

### Backend tests

- Create `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilderTest.java`.
- Create `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingServiceTest.java`.
- Create `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingCleanupServiceTest.java`.
- Create `src/test/java/com/aitaskcenter/controller/TaskOnboardingControllerTest.java`.

### Frontend

- Modify `web-react/src/api.ts`: onboarding types and API methods.
- Create `web-react/src/TaskOnboardingDrawer.tsx`: seven-node flow and current-step content.
- Modify `web-react/src/App.tsx`: open drawer from task rows, remove direct generation handlers and batch modal.
- Modify `web-react/src/styles.css`: responsive drawer, flow nodes, locked/completed/active states, validation details.

## Task 1: Persist Canonical Workflow State

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/aitaskcenter/model/TaskConfig.java`
- Create: `src/main/java/com/aitaskcenter/service/onboarding/OnboardingStep.java`
- Create: `src/main/java/com/aitaskcenter/service/onboarding/OnboardingStatus.java`
- Create: `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingContext.java`
- Test: `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingServiceTest.java`

- [ ] **Step 1: Add the test dependency**

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the failing default-state test**

```java
@Test
void initializesUnconfiguredTaskAtResultCodeStep() {
    TaskConfig task = new TaskConfig();
    assertEquals("RESULT_CODE", task.getOnboardingStep());
    assertEquals("ACTIVE", task.getOnboardingStatus());
    assertEquals("{}", task.getOnboardingContext());
}
```

- [ ] **Step 3: Run the focused test and confirm failure**

Run: `mvn -Dtest=TaskOnboardingServiceTest#initializesUnconfiguredTaskAtResultCodeStep test`

Expected: compilation failure because onboarding accessors do not exist.

- [ ] **Step 4: Add enums and persisted fields**

```java
public enum OnboardingStep {
    RESULT_CODE,
    RESULT_VALIDATION,
    RESULT_GENERATION,
    BATCH_CODE,
    BATCH_VALIDATION,
    BATCH_GENERATION,
    READY
}

public enum OnboardingStatus {
    ACTIVE,
    COMPLETED,
    FAILED,
    STALE
}
```

Add to `TaskConfig` with matching getters/setters:

```java
@Column(nullable = false, length = 40)
private String onboardingStep = OnboardingStep.RESULT_CODE.name();

@Column(nullable = false, length = 40)
private String onboardingStatus = OnboardingStatus.ACTIVE.name();

@Column(nullable = false, columnDefinition = "text")
private String onboardingContext = "{}";
```

Define `TaskOnboardingContext` with empty defaults and Jackson-compatible getters/setters for:

```java
private String resultValidationRunId = "";
private String resultReportToken = "";
private List<Long> resultValidationIds = new ArrayList<>();
private String resultArtifactPath = "";
private String resultArtifactHash = "";
private String batchValidationMarker = "";
private String batchReportToken = "";
private Long batchValidationTaskRunId;
private List<Long> batchValidationResultIds = new ArrayList<>();
private String batchArtifactPath = "";
private String batchArtifactHash = "";
private String errorMessage = "";
```

- [ ] **Step 5: Run the test suite**

Run: `mvn test`

Expected: all tests pass.

- [ ] **Step 6: Commit the state model**

```bash
git add pom.xml src/main/java/com/aitaskcenter/model/TaskConfig.java src/main/java/com/aitaskcenter/service/onboarding src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingServiceTest.java
git commit -m "feat: persist task onboarding state"
```

## Task 2: Generate Strict Codex Prompts

**Files:**
- Create: `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilder.java`
- Test: `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilderTest.java`

- [ ] **Step 1: Write result-prompt contract tests**

```java
@Test
void resultPromptForbidsBatchCreationAndRequiresExactMarker() {
    String prompt = builder.buildResultPrompt(task, "result-run-1", "token-1");
    assertTrue(prompt.contains("RESULT_VALIDATION:result-run-1"));
    assertTrue(prompt.contains("最多写入 3 条 tb_task_result"));
    assertTrue(prompt.contains("禁止创建 tb_task_run"));
    assertTrue(prompt.contains("--stage result"));
    assertTrue(prompt.contains("--token token-1"));
}
```

- [ ] **Step 2: Write batch-prompt contract tests**

```java
@Test
void batchPromptForbidsResultMutationAndTaskExecution() {
    String prompt = builder.buildBatchPrompt(task, "batch-run-1", "token-2");
    assertTrue(prompt.contains("BATCH_VALIDATION:batch-run-1"));
    assertTrue(prompt.contains("只创建 1 个 tb_task_run"));
    assertTrue(prompt.contains("禁止新增、修改或删除 tb_task_result"));
    assertTrue(prompt.contains("禁止启动验证批次"));
    assertTrue(prompt.contains("--stage batch"));
}
```

- [ ] **Step 3: Run tests and confirm failure**

Run: `mvn -Dtest=TaskOnboardingPromptBuilderTest test`

Expected: compilation failure because the builder is missing.

- [ ] **Step 4: Implement the prompt builder**

Create a Spring `@Component` whose two public methods are:

```java
public String buildResultPrompt(TaskConfig task, String validationRunId, String token)
public String buildBatchPrompt(TaskConfig task, String validationRunId, String token)
```

Both methods include task ID/name, selected tables, task description, exact marker, maximum validation row count, forbidden actions, and the full `./scripts/task-workflow report` callback command. Escape user-provided task text as quoted JSON using `ObjectMapper.writeValueAsString` before embedding it.

- [ ] **Step 5: Run focused tests**

Run: `mvn -Dtest=TaskOnboardingPromptBuilderTest test`

Expected: both prompt tests pass.

- [ ] **Step 6: Commit prompt generation**

```bash
git add src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilder.java src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilderTest.java
git commit -m "feat: generate onboarding codex prompts"
```

## Task 3: Validate Codex Callbacks and Build the Seven-Node Read Model

**Files:**
- Create: `src/main/java/com/aitaskcenter/dto/TaskOnboardingReportRequest.java`
- Create: `src/main/java/com/aitaskcenter/dto/TaskOnboardingNodeResponse.java`
- Create: `src/main/java/com/aitaskcenter/dto/TaskOnboardingResponse.java`
- Create: `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingService.java`
- Modify: `src/main/java/com/aitaskcenter/repository/TaskResultRepository.java`
- Modify: `src/main/java/com/aitaskcenter/repository/TaskRunRepository.java`
- Modify: `src/main/java/com/aitaskcenter/repository/TaskRunResultRepository.java`
- Test: `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingServiceTest.java`

- [ ] **Step 1: Add failing callback-transition tests**

Cover these cases with Mockito repositories:

```java
@Test
void acceptsUpToThreeExactlyMarkedResultRowsAndAdvancesToReview() { ... }

@Test
void rejectsResultCallbackWhenAnyResultHasRunLinks() { ... }

@Test
void rejectsReusedCallbackToken() { ... }

@Test
void acceptsExactlyOneUnstartedMarkedBatchWithoutChangingResults() { ... }

@Test
void returnsAllSevenNodesWithOnlyCurrentNodeActive() { ... }
```

For the happy result callback, assert:

```java
assertEquals(OnboardingStep.RESULT_VALIDATION.name(), saved.getOnboardingStep());
assertEquals(List.of(101L, 102L), context.getResultValidationIds());
assertEquals("", context.getResultReportToken());
```

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `mvn -Dtest=TaskOnboardingServiceTest test`

Expected: compilation failures for missing DTOs/service/repository methods.

- [ ] **Step 3: Add exact repository methods**

```java
List<TaskResult> findByTaskConfigIdAndSourceDescriptionOrderByIdAsc(
        Long taskConfigId, String sourceDescription);

List<TaskRun> findByTaskConfigIdAndReasonOrderByIdAsc(
        Long taskConfigId, String reason);

long countByTaskResultIdIn(Collection<Long> taskResultIds);
```

- [ ] **Step 4: Implement callback DTO and state service**

`TaskOnboardingReportRequest` fields:

```java
private String stage;
private String token;
private String artifact;
private String artifactHash;
private List<Long> entityIds = new ArrayList<>();
```

`TaskOnboardingService` public API:

```java
public TaskOnboardingResponse get(Long taskConfigId)
public TaskOnboardingResponse report(Long taskConfigId, TaskOnboardingReportRequest request)
public TaskOnboardingResponse confirmResultValidation(Long taskConfigId)
public TaskOnboardingResponse confirmBatchValidation(Long taskConfigId)
```

Use `SecureRandom`/UUID tokens, parse and serialize `TaskOnboardingContext` with `ObjectMapper`, and define a single transition map. Never accept a target step from a request. Build all seven nodes server-side with `COMPLETED`, `ACTIVE`, or `LOCKED` based on the persisted current step.

- [ ] **Step 5: Run focused and full tests**

Run: `mvn -Dtest=TaskOnboardingServiceTest test && mvn test`

Expected: all callback, token, marker, and node-state tests pass.

- [ ] **Step 6: Commit callback validation**

```bash
git add src/main/java/com/aitaskcenter/dto src/main/java/com/aitaskcenter/repository src/main/java/com/aitaskcenter/service/onboarding src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingServiceTest.java
git commit -m "feat: validate onboarding callbacks"
```

## Task 4: Clean Validation Data and Gate Formal Generation

**Files:**
- Create: `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingCleanupService.java`
- Modify: `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingService.java`
- Modify: `src/main/java/com/aitaskcenter/service/TaskConfigService.java`
- Test: `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingCleanupServiceTest.java`
- Test: `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingServiceTest.java`

- [ ] **Step 1: Write failing cleanup safety tests**

```java
@Test
void resultCleanupUsesTaskAndExactMarkerAndRefusesLinkedRows() { ... }

@Test
void batchCleanupDeletesLogsThenLinksThenRunWithoutDeletingResults() { ... }

@Test
void failedCleanupDoesNotCallFormalGeneration() { ... }

@Test
void successfulResultGenerationAdvancesOnlyToBatchCode() { ... }
```

Use Mockito `InOrder` to assert batch deletion order and verify no SQL contains `delete from tb_task_result` in batch cleanup.

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `mvn -Dtest=TaskOnboardingCleanupServiceTest,TaskOnboardingServiceTest test`

Expected: failures because cleanup/generation methods are missing.

- [ ] **Step 3: Implement transactional cleanup service**

Public methods:

```java
@Transactional
public int deleteResultValidation(Long taskConfigId, String validationRunId)

@Transactional
public int deleteBatchValidation(Long taskConfigId, Long taskRunId, String validationRunId)
```

Result cleanup first counts run links for the exact validation result IDs and throws if nonzero. Batch cleanup deletes from `tb_task_execution_log`, `tb_task_run_result`, then `tb_task_run` using task ID, run ID, and exact reason marker. Requery after deletion and throw unless zero rows remain.

- [ ] **Step 4: Add gated generation methods**

```java
public TaskOnboardingResponse generateResults(Long taskConfigId)
public TaskOnboardingResponse generateBatches(Long taskConfigId, GenerateTaskRunBatchRequest request)
```

`generateResults` requires `RESULT_GENERATION`, calls result validation cleanup, then `TaskConfigService.generateResults(taskConfigId, false)`, and advances only when `insertedCount > 0`. `generateBatches` requires `BATCH_GENERATION`, cleans only the validation run, delegates to `generateRunBatches`, and advances only when `createdRunCount > 0` and `linkedResultCount > 0`.

- [ ] **Step 5: Reset stale workflows when semantic configuration changes**

In `TaskConfigService.update`, compare old/new values for project ID, CLI ID, database config ID, selected tables, and task description. If any differ, reset step/status/context to `RESULT_CODE`/`ACTIVE`/`{}`. Do not reset when only task name changes.

- [ ] **Step 6: Run full backend tests**

Run: `mvn test`

Expected: all tests pass and cleanup SQL assertions prove result/batch separation.

- [ ] **Step 7: Commit generation gates**

```bash
git add src/main/java/com/aitaskcenter/service src/test/java/com/aitaskcenter/service/onboarding
git commit -m "feat: gate formal task generation"
```

## Task 5: Expose HTTP API and Codex Callback Script

**Files:**
- Create: `src/main/java/com/aitaskcenter/controller/TaskOnboardingController.java`
- Create: `src/test/java/com/aitaskcenter/controller/TaskOnboardingControllerTest.java`
- Create: `scripts/task-workflow`

- [ ] **Step 1: Write failing MockMvc endpoint tests**

Test these routes and verify API envelopes:

```text
GET  /api/task/{id}/onboarding
POST /api/task/{id}/onboarding/report
POST /api/task/{id}/onboarding/result-validation/confirm
POST /api/task/{id}/onboarding/results/generate
POST /api/task/{id}/onboarding/batch-validation/confirm
POST /api/task/{id}/onboarding/batches/generate
```

Also assert an invalid report returns the existing `ApiExceptionHandler` error envelope.

- [ ] **Step 2: Run controller tests and confirm failure**

Run: `mvn -Dtest=TaskOnboardingControllerTest test`

Expected: route-not-found failures.

- [ ] **Step 3: Implement the controller**

Use `/api/task/{id}/onboarding` as the base mapping and delegate every method directly to `TaskOnboardingService`. Batch generation accepts the existing `GenerateTaskRunBatchRequest` body.

- [ ] **Step 4: Implement `scripts/task-workflow`**

Create an executable Python 3 script using only `argparse`, `json`, and `urllib.request`. It accepts only the `report` command and the six documented flags, validates required values, serializes JSON with `json.dumps`, and posts to:

```text
${TASK_CENTER_BASE_URL:-http://127.0.0.1:18743}/api/task/${taskConfigId}/onboarding/report
```

It exits nonzero on missing arguments or non-2xx responses and prints the backend message.

- [ ] **Step 5: Verify script and controller**

Run: `scripts/task-workflow --help`

Expected: usage text and exit code 0.

Run: `mvn test`

Expected: all tests pass.

- [ ] **Step 6: Commit API and script**

```bash
git add src/main/java/com/aitaskcenter/controller/TaskOnboardingController.java src/test/java/com/aitaskcenter/controller/TaskOnboardingControllerTest.java scripts/task-workflow
git commit -m "feat: expose onboarding callback api"
```

## Task 6: Build the Seven-Step React Drawer

**Files:**
- Modify: `web-react/src/api.ts`
- Create: `web-react/src/TaskOnboardingDrawer.tsx`
- Modify: `web-react/src/App.tsx`
- Modify: `web-react/src/styles.css`

- [ ] **Step 1: Add API types and methods**

Define:

```ts
export type OnboardingStep =
  | 'RESULT_CODE' | 'RESULT_VALIDATION' | 'RESULT_GENERATION'
  | 'BATCH_CODE' | 'BATCH_VALIDATION' | 'BATCH_GENERATION' | 'READY';

export type OnboardingNodeState = 'COMPLETED' | 'ACTIVE' | 'LOCKED' | 'FAILED' | 'STALE';

export interface TaskOnboardingNode {
  step: OnboardingStep;
  label: string;
  state: OnboardingNodeState;
}

export interface TaskOnboardingResponse {
  task: TaskConfig;
  nodes: TaskOnboardingNode[];
  currentStep: OnboardingStep;
  currentStatus: string;
  prompt?: string;
  validationResults?: TaskResult[];
  validationRun?: TaskRun;
  validationRunResults?: TaskResult[];
  counts: Record<string, number>;
  allowedActions: string[];
  errorMessage?: string;
}
```

Add one Axios method per backend endpoint.

- [ ] **Step 2: Create the drawer component**

`TaskOnboardingDrawer` props:

```ts
interface Props {
  open: boolean;
  task: TaskConfig | null;
  projects: ProjectConfig[];
  connections: ConnectionConfig[];
  cliConfigs: LocalCliConfigItem[];
  onClose: () => void;
  onReady: (taskConfigId: number) => void;
}
```

The component:

- fetches on open and on `window.focus`;
- renders all seven nodes at all times;
- allows completed nodes to show a read-only summary;
- prevents locked-node requests and shows `请先完成上一步`;
- renders only backend-listed actions;
- copies prompts with `navigator.clipboard.writeText`;
- shows result validation rows only at `RESULT_VALIDATION`;
- shows batch validation data only at `BATCH_VALIDATION`;
- owns the batch-size form only at `BATCH_GENERATION`;
- never applies optimistic step transitions.

- [ ] **Step 3: Integrate into the task table**

In `App.tsx`:

- replace “生成结果”“生成批次” with one “详情” link;
- remove `generatingResultId`, `runBatchModalOpen`, `runBatchTask`, `runBatchForm`, and `generatingRunBatches` state;
- remove `generateResultsForTask`, `openRunBatchModal`, `createRunBatches`, and the old batch modal;
- mount `TaskOnboardingDrawer` once near the existing modals;
- on `onReady`, switch to the task-run module and apply the current task-config filter.

- [ ] **Step 4: Add responsive styles**

Use a desktop drawer width of `min(900px, 82vw)` and full width below 768px. The flow is a single horizontal row with overflow scrolling. Active nodes use the existing primary purple, completed nodes use green, and locked nodes use neutral gray. Keep content sections unframed except for prompt/code and tabular validation data.

- [ ] **Step 5: Build the frontend**

Run: `npm run build`

Expected: TypeScript and Vite build pass; the existing bundle-size warning may remain.

- [ ] **Step 6: Commit frontend integration**

```bash
git add web-react/src/api.ts web-react/src/TaskOnboardingDrawer.tsx web-react/src/App.tsx web-react/src/styles.css
git commit -m "feat: add task onboarding drawer"
```

## Task 7: End-to-End Verification and UI QA

**Files:**
- Modify only files required by defects found during verification.

- [ ] **Step 1: Run all automated checks**

Run: `mvn test`

Expected: all backend tests pass with zero failures.

Run: `npm run build`

Expected: frontend build passes.

Run: `python3 -m py_compile python-worker/app/main.py`

Expected: no output and exit code 0.

- [ ] **Step 2: Start the three project services**

Run from the worktree: `./scripts/start-dev.sh`

Expected:

- frontend responds at `http://127.0.0.1:19637/`;
- Java onboarding endpoint responds at `/api/task/{id}/onboarding`;
- Python Worker responds at `http://127.0.0.1:19186/api/health`;
- existing local PostgreSQL on port 5432 is reused and no Docker PostgreSQL is created.

- [ ] **Step 3: Exercise the legal workflow with API calls**

For a disposable task configuration, verify:

1. initial response has seven nodes and only `RESULT_CODE` active;
2. locked steps reject direct execution;
3. a valid result callback advances to `RESULT_VALIDATION`;
4. result confirmation and formal generation clean only exact validation rows;
5. a valid batch callback advances to `BATCH_VALIDATION` without changing result count;
6. batch confirmation and generation clean only the validation run;
7. final state is `READY` and formal runs appear in the task list.

- [ ] **Step 4: Verify desktop and mobile UI with Playwright**

Capture screenshots at 1440×900 and 390×844. Assert:

- all seven nodes remain visible or horizontally scrollable;
- only current-step actions are enabled;
- locked-node click shows the gate message;
- no text or buttons overlap;
- the drawer is full width on mobile;
- completed nodes can be opened read-only;
- the final “查看任务列表” navigation applies the task filter.

- [ ] **Step 5: Run final diff and regression checks**

Run:

```bash
git diff --check
git status --short
mvn test
cd web-react && npm run build
```

Expected: no whitespace errors, only intended files changed, all tests/builds pass.

- [ ] **Step 6: Commit verification fixes**

```bash
git add pom.xml scripts/task-workflow src/main/java src/test/java web-react/src
git commit -m "test: verify task onboarding workflow"
```

If verification produced no file changes, skip this commit and report that no follow-up fixes were required.
