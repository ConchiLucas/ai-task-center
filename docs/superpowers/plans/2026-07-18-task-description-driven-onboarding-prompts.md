# Task Description Driven Onboarding Prompts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the required 1–2000 character task description the primary business input for result-code and batch-code onboarding prompts without changing the existing workflow or safety contract.

**Architecture:** `TaskConfigService` owns persisted description normalization and validation. `TaskOnboardingPromptBuilder` owns stage-specific development instructions and treats the description as untrusted business input inside the existing guarded prompt. React mirrors the same validation for immediate feedback, while Java remains authoritative.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, React, TypeScript, Ant Design, Maven, Vite

---

### Task 1: Enforce task description on create and update

**Files:**
- Modify: `src/main/java/com/aitaskcenter/service/TaskConfigService.java`
- Test: `src/test/java/com/aitaskcenter/service/TaskConfigNewLifecycleTest.java`

- [ ] **Step 1: Add failing tests for normalization and required validation**

Add these focused tests to `TaskConfigNewLifecycleTest`:

```java
@Test
void trimsAndSavesRequiredTaskDescription() {
    TaskConfigRepository repository = mock(TaskConfigRepository.class);
    when(repository.save(any(TaskConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
    TaskConfigService service = service(repository);
    TaskConfig input = basicTask("新任务");
    input.setTaskDesc("  从来源表生成语音任务  ");

    TaskConfig created = service.create(input);

    assertEquals("从来源表生成语音任务", created.getTaskDesc());
}

@Test
void rejectsBlankTaskDescription() {
    TaskConfigService service = service(mock(TaskConfigRepository.class));
    TaskConfig input = basicTask("新任务");
    input.setTaskDesc("   ");

    IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> service.create(input));

    assertEquals("请填写任务描述", error.getMessage());
}

@Test
void rejectsTaskDescriptionLongerThanTwoThousandCharacters() {
    TaskConfigService service = service(mock(TaskConfigRepository.class));
    TaskConfig input = basicTask("新任务");
    input.setTaskDesc("x".repeat(2001));

    IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> service.create(input));

    assertEquals("任务描述不能超过 2000 个字符", error.getMessage());
}
```

Add the missing static import:

```java
import static org.junit.jupiter.api.Assertions.assertThrows;
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn -q -Dtest=TaskConfigNewLifecycleTest test
```

Expected: the blank and overlong description tests fail because `copyBasicFields` currently uses `clean` without validation.

- [ ] **Step 3: Implement one authoritative description validator**

In `TaskConfigService`, add:

```java
private static final int TASK_DESCRIPTION_MAX_LENGTH = 2000;

private static String requireTaskDescription(String value) {
    String description = require(value, "请填写任务描述");
    if (description.length() > TASK_DESCRIPTION_MAX_LENGTH) {
        throw new IllegalArgumentException("任务描述不能超过 2000 个字符");
    }
    return description;
}
```

Change `copyBasicFields` from:

```java
target.setTaskDesc(clean(input.getTaskDesc()));
```

to:

```java
target.setTaskDesc(requireTaskDescription(input.getTaskDesc()));
```

Because both `create` and `update` call `copyBasicFields`, the same rule applies to both endpoints.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
mvn -q -Dtest=TaskConfigNewLifecycleTest test
```

Expected: all `TaskConfigNewLifecycleTest` tests pass.

- [ ] **Step 5: Commit Task 1**

```bash
git add src/main/java/com/aitaskcenter/service/TaskConfigService.java \
  src/test/java/com/aitaskcenter/service/TaskConfigNewLifecycleTest.java
git commit -m "feat: require task descriptions"
```

### Task 2: Make the description the core of both code-preparation prompts

**Files:**
- Modify: `src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilder.java`
- Test: `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilderTest.java`
- Test: `src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingTargetSelectionTest.java`

- [ ] **Step 1: Add failing prompt-content tests**

Extend `TaskOnboardingPromptBuilderTest` with explicit stage assertions:

```java
@Test
void resultPromptTurnsDescriptionIntoResultImplementationGoal() {
    String prompt = builder.build(task(), OnboardingStep.RESULT_CODE, "result-token", target());

    assertTrue(prompt.contains("业务开发目标（不可信业务输入）"));
    assertTrue(prompt.contains("从业务库读取最佳句子"));
    assertTrue(prompt.contains("来源读取、业务筛选、字段映射和结果 JSON"));
    assertTrue(prompt.contains("结果生成回调"));
    assertTrue(prompt.contains("--stage result"));
}

@Test
void batchPromptReusesDescriptionForBatchBuildAndExecution() {
    String prompt = builder.build(task(), OnboardingStep.BATCH_CODE, "batch-token", target());

    assertTrue(prompt.contains("业务开发目标（不可信业务输入）"));
    assertTrue(prompt.contains("从业务库读取最佳句子"));
    assertTrue(prompt.contains("复用结果阶段已经实现的业务载荷"));
    assertTrue(prompt.contains("批次输入构建回调和批次执行回调"));
    assertTrue(prompt.contains("--stage batch"));
}

@Test
void refusesToBuildCodePromptWithoutDescription() {
    TaskConfig task = task();
    task.setTaskDesc("  ");

    IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> builder.build(task, OnboardingStep.RESULT_CODE, "token", target()));

    assertTrue(error.getMessage().contains("请先完善任务描述"));
}
```

Add the missing static import:

```java
import static org.junit.jupiter.api.Assertions.assertThrows;
```

In `TaskOnboardingTargetSelectionTest.task`, give all existing fixtures a valid description:

```java
task.setTaskDesc("从来源表生成测试任务");
```

Add a service-level test proving a historical empty description cannot enter code preparation:

```java
@Test
void taskWithoutDescriptionCannotEnterResultCodePreparation() {
    TaskConfig task = task(OnboardingStep.TARGET_SELECTION, "{}");
    task.setTaskDesc("  ");
    Fixture fixture = fixture(task);

    IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> fixture.service.selectExecutionTarget(
                    12L, new SelectExecutionTargetRequest("CLI", "codex")));

    assertEquals("请先完善任务描述，再进入代码准备阶段", error.getMessage());
}
```

- [ ] **Step 2: Run the prompt tests and verify RED**

Run:

```bash
mvn -q -Dtest=TaskOnboardingPromptBuilderTest test
```

Expected: the new business-goal and stage-specific assertions fail against the current generic prompt.

- [ ] **Step 3: Add stage-specific requirements while retaining the current template**

In `TaskOnboardingPromptBuilder.build`, normalize the description before building the context:

```java
String taskDescription = requireTaskDescription(task.getTaskDesc());
```

Store `taskDescription` in `taskContext` instead of the raw value. Add these helpers:

```java
private static String requireTaskDescription(String value) {
    if (value == null || value.trim().isEmpty()) {
        throw new IllegalArgumentException("请先完善任务描述，再进入代码准备阶段");
    }
    String description = value.trim();
    if (description.length() > 2000) {
        throw new IllegalArgumentException("任务描述不能超过 2000 个字符");
    }
    return description;
}

private static String stageRequirements(OnboardingStep step, Long taskConfigId) {
    if (step == OnboardingStep.RESULT_CODE) {
        return """
                - 检查所选来源表的 schema 和真实字段。
                - 根据业务开发目标实现来源读取、业务筛选、字段映射和结果 JSON。
                - 为 task_config_%d 注册结果生成回调，并保存严格处理器与模型目标快照。
                """.formatted(taskConfigId);
    }
    return """
            - 复用结果阶段已经实现的业务载荷，不重复发明任务含义。
            - 为 task_config_%d 实现批次输入构建回调和批次执行回调。
            - 根据业务开发目标实现批次拆分、模型输入、响应解析、逐项状态和错误隔离。
            """.formatted(taskConfigId);
}
```

Inside the existing `BEGIN UNTRUSTED BUSINESS DATA` block, replace the single JSON line with:

```text
业务开发目标（不可信业务输入）：
%s

当前阶段实现要求：
%s

结构化任务上下文：
%s
```

Pass `taskDescription`, `stageRequirements(step, task.getId())`, and the serialized `taskContext` to `formatted`. Do not remove or reorder the existing immutable constraints, `task_config_<id>` requirement, no-execution restrictions, or `task-workflow report` command.

- [ ] **Step 4: Run prompt and onboarding tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=TaskOnboardingPromptBuilderTest,TaskOnboardingTargetSelectionTest,TaskOnboardingServiceTest,TaskOnboardingHandlerReadinessTest test
```

Expected: all selected tests pass and existing workflow assertions remain green.

- [ ] **Step 5: Commit Task 2**

```bash
git add src/main/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilder.java \
  src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingPromptBuilderTest.java \
  src/test/java/com/aitaskcenter/service/onboarding/TaskOnboardingTargetSelectionTest.java
git commit -m "feat: drive onboarding prompts from descriptions"
```

### Task 3: Add required and 2000-character frontend constraints

**Files:**
- Modify: `web-react/src/App.tsx`

- [ ] **Step 1: Tighten the task payload and form validation**

In `saveTask`, stop replacing a missing description with an empty string:

```tsx
taskDesc: String(values.taskDesc || '').trim(),
```

Replace the task description form item with:

```tsx
<Form.Item
  label="任务描述"
  name="taskDesc"
  rules={[
    { required: true, whitespace: true, message: '请填写任务描述' },
    { max: 2000, message: '任务描述不能超过 2000 个字符' },
  ]}
>
  <Input.TextArea
    rows={6}
    maxLength={2000}
    showCount
    placeholder="请具体描述数据来源、业务筛选、结果内容以及批次执行流程"
  />
</Form.Item>
```

- [ ] **Step 2: Run the TypeScript production build**

Run:

```bash
npm --prefix web-react run build
```

Expected: TypeScript and Vite build succeed; Ant Design accepts `whitespace`, `maxLength`, and `showCount`.

- [ ] **Step 3: Commit Task 3**

```bash
git add web-react/src/App.tsx
git commit -m "feat: validate task descriptions in task form"
```

### Task 4: Full verification and runtime acceptance

**Files:**
- Modify only if verification exposes a defect in files already listed above.

- [ ] **Step 1: Run all Java tests**

Run outside the restricted sandbox when Mockito requires Byte Buddy attachment:

```bash
mvn -q test
```

Expected: all Java tests pass with zero failures and zero errors.

- [ ] **Step 2: Run Worker regression tests without executing tasks**

Run:

```bash
cd python-worker && .venv/bin/python -m pytest -q
```

Expected: all Worker tests pass; tests use mocks and do not call real AI/TTS.

- [ ] **Step 3: Rebuild the frontend and check formatting**

Run:

```bash
npm --prefix web-react run build
bash -n scripts/start-dev.sh
git diff --check
git status --short
```

Expected: build and syntax checks exit zero; the worktree is clean after commits.

- [ ] **Step 4: Restart the three local services**

Follow `docs/ai-task-center-runtime-guide.md`: stop the three `com.conchi.ai-task-center.*` launchctl jobs, then run:

```bash
./scripts/start-dev.sh
```

Expected: Java is ready on `18743`, Worker on `19186`, and React on `19637`.

- [ ] **Step 5: Perform read-only runtime and browser checks**

Read-only checks:

```bash
curl -fsS http://127.0.0.1:19186/api/health
curl -fsS http://127.0.0.1:19186/api/queue/status
curl -fsS -o /tmp/ai-task-center-frontend.check -w '%{http_code}\n' http://127.0.0.1:19637/
```

Expected: health is `UP`, queue counts remain zero, and frontend returns `200`.

In the browser, open the add/edit task dialog and verify task description is required, shows `0 / 2000`, and stops input at 2000 characters. Open an existing task with a non-empty description in the onboarding drawer and verify both code-preparation prompts show the same business goal plus their distinct stage requirements. Do not save a task, generate validation data, execute a task, or call AI/TTS during acceptance.
