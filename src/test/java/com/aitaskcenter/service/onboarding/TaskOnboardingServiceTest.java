package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.dto.TaskOnboardingReportRequest;
import com.aitaskcenter.dto.TaskOnboardingResponse;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.model.TaskRunResult;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskOnboardingServiceTest {
    private static final Long TASK_ID = 7L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TaskConfigRepository taskConfigRepository;
    private TaskResultRepository taskResultRepository;
    private TaskRunRepository taskRunRepository;
    private TaskRunResultRepository taskRunResultRepository;
    private TaskOnboardingPromptBuilder promptBuilder;
    private RepositoryStub<TaskConfigRepository> taskConfigs;
    private RepositoryStub<TaskResultRepository> taskResults;
    private RepositoryStub<TaskRunRepository> taskRuns;
    private RepositoryStub<TaskRunResultRepository> taskRunResults;

    private TaskOnboardingService service;

    @BeforeEach
    void setUp() {
        taskConfigs = new RepositoryStub<>(TaskConfigRepository.class);
        taskResults = new RepositoryStub<>(TaskResultRepository.class);
        taskRuns = new RepositoryStub<>(TaskRunRepository.class);
        taskRunResults = new RepositoryStub<>(TaskRunResultRepository.class);
        taskConfigRepository = taskConfigs.proxy();
        taskResultRepository = taskResults.proxy();
        taskRunRepository = taskRuns.proxy();
        taskRunResultRepository = taskRunResults.proxy();
        promptBuilder = new TaskOnboardingPromptBuilder(OBJECT_MAPPER);
        service = new TaskOnboardingService(
                taskConfigRepository,
                taskResultRepository,
                taskRunRepository,
                taskRunResultRepository,
                promptBuilder,
                OBJECT_MAPPER);
    }

    @Test
    void initializesUnconfiguredTaskAtResultCodeStep() {
        TaskConfig task = new TaskConfig();
        assertEquals("RESULT_CODE", task.getOnboardingStep());
        assertEquals("ACTIVE", task.getOnboardingStatus());
        assertEquals("{}", task.getOnboardingContext());
    }

    @Test
    void initializesJacksonContextWithEmptyDefaults() throws Exception {
        TaskOnboardingContext context = OBJECT_MAPPER.readValue("{}", TaskOnboardingContext.class);

        assertEquals("", context.getResultValidationRunId());
        assertEquals("", context.getResultReportToken());
        assertEquals(0, context.getResultValidationIds().size());
        assertEquals("", context.getResultArtifactPath());
        assertEquals("", context.getResultArtifactHash());
        assertEquals("", context.getBatchValidationMarker());
        assertEquals("", context.getBatchReportToken());
        assertNull(context.getBatchValidationTaskRunId());
        assertEquals(0, context.getBatchValidationResultIds().size());
        assertEquals("", context.getBatchArtifactPath());
        assertEquals("", context.getBatchArtifactHash());
        assertEquals("", context.getErrorMessage());
    }

    @Test
    void roundTripsFullyPopulatedContextWithJackson() throws Exception {
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setResultValidationRunId("result-run-1");
        context.setResultReportToken("result-token");
        context.setResultValidationIds(List.of(11L, 12L));
        context.setResultArtifactPath("/tmp/result.json");
        context.setResultArtifactHash("result-hash");
        context.setBatchValidationMarker("batch-marker");
        context.setBatchReportToken("batch-token");
        context.setBatchValidationTaskRunId(21L);
        context.setBatchValidationResultIds(List.of(31L, 32L));
        context.setBatchArtifactPath("/tmp/batch.json");
        context.setBatchArtifactHash("batch-hash");
        context.setErrorMessage("validation failed");

        String json = OBJECT_MAPPER.writeValueAsString(context);
        TaskOnboardingContext restored = OBJECT_MAPPER.readValue(json, TaskOnboardingContext.class);

        assertEquals("result-run-1", restored.getResultValidationRunId());
        assertEquals("result-token", restored.getResultReportToken());
        assertEquals(List.of(11L, 12L), restored.getResultValidationIds());
        assertEquals("/tmp/result.json", restored.getResultArtifactPath());
        assertEquals("result-hash", restored.getResultArtifactHash());
        assertEquals("batch-marker", restored.getBatchValidationMarker());
        assertEquals("batch-token", restored.getBatchReportToken());
        assertEquals(21L, restored.getBatchValidationTaskRunId());
        assertEquals(List.of(31L, 32L), restored.getBatchValidationResultIds());
        assertEquals("/tmp/batch.json", restored.getBatchArtifactPath());
        assertEquals("batch-hash", restored.getBatchArtifactHash());
        assertEquals("validation failed", restored.getErrorMessage());
    }

    @Test
    void acceptsUpToThreeExactlyMarkedResultRowsAndAdvancesToReview() throws Exception {
        TaskConfig task = resultCodeTask("result-run-1", "result-token");
        List<TaskResult> results = List.of(
                result(101L, TASK_ID, "RESULT_VALIDATION:result-run-1"),
                result(102L, TASK_ID, "RESULT_VALIDATION:result-run-1"));
        stubTask(task);
        taskResults.answer("findByTaskConfigIdAndSourceDescriptionOrderByIdAsc",
                results, TASK_ID, "RESULT_VALIDATION:result-run-1");
        taskRunResults.answer("countByTaskResultIdIn", 0L, List.of(101L, 102L));

        TaskOnboardingResponse response = service.report(
                TASK_ID, report("result", "result-token", List.of(101L, 102L)));

        TaskOnboardingContext savedContext = context(task);
        assertEquals(OnboardingStep.RESULT_VALIDATION.name(), task.getOnboardingStep());
        assertEquals(List.of(101L, 102L), savedContext.getResultValidationIds());
        assertEquals("", savedContext.getResultReportToken());
        assertEquals("src/result-generator.py", savedContext.getResultArtifactPath());
        assertEquals("sha256-result", savedContext.getResultArtifactHash());
        assertEquals(OnboardingStep.RESULT_VALIDATION.name(), response.getCurrentStep());
        assertEquals(1, taskConfigs.callCount("save"));
    }

    @Test
    void rejectsResultCallbackWhenAnyResultHasRunLinks() {
        TaskConfig task = resultCodeTask("result-run-1", "result-token");
        stubTask(task);
        taskResults.answer("findByTaskConfigIdAndSourceDescriptionOrderByIdAsc",
                List.of(result(101L, TASK_ID, "RESULT_VALIDATION:result-run-1")),
                TASK_ID, "RESULT_VALIDATION:result-run-1");
        taskRunResults.answer("countByTaskResultIdIn", 1L, List.of(101L));

        assertThrows(IllegalArgumentException.class,
                () -> service.report(TASK_ID, report("result", "result-token", List.of(101L))));

        assertEquals(OnboardingStep.RESULT_CODE.name(), task.getOnboardingStep());
        assertEquals(0, taskConfigs.callCount("save"));
    }

    @Test
    void rejectsReusedCallbackToken() throws Exception {
        TaskConfig task = resultCodeTask("result-run-1", "result-token");
        stubTask(task);
        taskResults.answer("findByTaskConfigIdAndSourceDescriptionOrderByIdAsc",
                List.of(result(101L, TASK_ID, "RESULT_VALIDATION:result-run-1")),
                TASK_ID, "RESULT_VALIDATION:result-run-1");
        taskRunResults.answer("countByTaskResultIdIn", 0L, List.of(101L));
        TaskOnboardingReportRequest request = report("result", "result-token", List.of(101L));

        service.report(TASK_ID, request);

        assertEquals("", context(task).getResultReportToken());
        task.setOnboardingStep(OnboardingStep.RESULT_CODE.name());
        assertThrows(IllegalArgumentException.class, () -> service.report(TASK_ID, request));
    }

    @Test
    void rejectsWrongTokenWrongStageZeroRowsTooManyRowsAndMismatchedResultData() {
        assertInvalidResultCallback(resultCodeTask("run", "token"), report("result", "wrong", List.of(1L)));
        assertInvalidResultCallback(resultCodeTask("run", "token"), report("batch", "token", List.of(1L)));
        assertInvalidResultCallback(resultCodeTask("run", "token"), report("result", "token", List.of()));
        assertInvalidResultCallback(
                resultCodeTask("run", "token"), report("result", "token", List.of(1L, 2L, 3L, 4L)));

        TaskConfig wrongStep = resultCodeTask("run", "token");
        wrongStep.setOnboardingStep(OnboardingStep.RESULT_VALIDATION.name());
        assertInvalidResultCallback(wrongStep, report("result", "token", List.of(1L)));

        TaskConfig duplicateIds = resultCodeTask("run", "token");
        stubTask(duplicateIds);
        assertThrows(IllegalArgumentException.class,
                () -> service.report(TASK_ID, report("result", "token", List.of(1L, 1L))));

        TaskConfig mismatchedIds = resultCodeTask("run", "token");
        stubTask(mismatchedIds);
        taskResults.answer("findByTaskConfigIdAndSourceDescriptionOrderByIdAsc",
                List.of(result(1L, TASK_ID, "RESULT_VALIDATION:run")),
                TASK_ID, "RESULT_VALIDATION:run");
        assertThrows(IllegalArgumentException.class,
                () -> service.report(TASK_ID, report("result", "token", List.of(2L))));

        TaskConfig wrongMarkerOrTask = resultCodeTask("run", "token");
        stubTask(wrongMarkerOrTask);
        taskResults.answer("findByTaskConfigIdAndSourceDescriptionOrderByIdAsc",
                List.of(result(1L, 999L, "RESULT_VALIDATION:other")),
                TASK_ID, "RESULT_VALIDATION:run");
        assertThrows(IllegalArgumentException.class,
                () -> service.report(TASK_ID, report("result", "token", List.of(1L))));
    }

    @Test
    void acceptsExactlyOneUnstartedMarkedBatchWithoutChangingResults() throws Exception {
        TaskConfig task = batchCodeTask("batch-run-1", "batch-token", List.of(201L, 202L));
        TaskRun run = run(301L, TASK_ID, "BATCH_VALIDATION:batch-run-1", "PENDING");
        List<TaskRunResult> links = List.of(link(401L, 301L, 201L), link(402L, 301L, 202L));
        stubTask(task);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run), TASK_ID, "BATCH_VALIDATION:batch-run-1");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", links, 301L);
        taskResults.answer("findByTaskConfigIdOrderByIdAsc",
                List.of(result(201L, TASK_ID, "formal"), result(202L, TASK_ID, "formal")), TASK_ID);
        taskRunResults.answer("countLinkedResultsForRunAndTask",
                2L, 301L, TASK_ID, List.of(201L, 202L));

        TaskOnboardingResponse response = service.report(
                TASK_ID, report("batch", "batch-token", List.of(301L, 201L, 202L)));

        TaskOnboardingContext savedContext = context(task);
        assertEquals(OnboardingStep.BATCH_VALIDATION.name(), task.getOnboardingStep());
        assertEquals(301L, savedContext.getBatchValidationTaskRunId());
        assertEquals(List.of(201L, 202L), savedContext.getBatchValidationResultIds());
        assertEquals("", savedContext.getBatchReportToken());
        assertEquals(OnboardingStep.BATCH_VALIDATION.name(), response.getCurrentStep());
        assertEquals(0, taskResults.callCount("save"));
        assertEquals(0, taskResults.callCount("saveAll"));
    }

    @Test
    void rejectsBatchWithInvalidRunMarkerTaskStateOrRelationships() {
        assertInvalidBatch(batchCodeTask("run", "token", List.of(11L)), report("batch", "token", List.of()));
        assertInvalidBatch(batchCodeTask("run", "token", List.of(11L)), report("batch", "token", List.of(9L)));

        TaskConfig noRun = batchCodeTask("run", "token", List.of(11L));
        stubTask(noRun);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc", List.of(), TASK_ID, "BATCH_VALIDATION:run");
        assertThrows(IllegalArgumentException.class,
                () -> service.report(TASK_ID, report("batch", "token", List.of(9L, 11L))));

        TaskConfig wrongCount = batchCodeTask("run", "token", List.of(11L));
        stubTask(wrongCount);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc", List.of(
                run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING"),
                run(10L, TASK_ID, "BATCH_VALIDATION:run", "PENDING")), TASK_ID, "BATCH_VALIDATION:run");
        assertThrows(IllegalArgumentException.class,
                () -> service.report(TASK_ID, report("batch", "token", List.of(9L, 11L))));

        TaskConfig wrongRunId = batchCodeTask("run", "token", List.of(11L));
        stubTask(wrongRunId);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING")),
                TASK_ID, "BATCH_VALIDATION:run");
        assertThrows(IllegalArgumentException.class,
                () -> service.report(TASK_ID, report("batch", "token", List.of(10L, 11L))));

        assertRejectedBatchRun(run(9L, TASK_ID, "BATCH_VALIDATION:run", "RUNNING"));
        TaskRun started = run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING");
        started.setStartTime(OffsetDateTime.now());
        assertRejectedBatchRun(started);
        assertRejectedBatchRun(run(9L, TASK_ID, "BATCH_VALIDATION:other", "PENDING"));
        assertRejectedBatchRun(run(9L, 999L, "BATCH_VALIDATION:run", "PENDING"));

        TaskConfig linkMismatch = batchCodeTask("run", "token", List.of(11L));
        stubTask(linkMismatch);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING")),
                TASK_ID, "BATCH_VALIDATION:run");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", List.of(link(21L, 9L, 12L)), 9L);
        assertThrows(IllegalArgumentException.class,
                () -> service.report(TASK_ID, report("batch", "token", List.of(9L, 11L))));

        TaskConfig otherTaskResult = batchCodeTask("run", "token", List.of(11L));
        stubTask(otherTaskResult);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING")),
                TASK_ID, "BATCH_VALIDATION:run");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", List.of(link(21L, 9L, 11L)), 9L);
        taskResults.answer("findByTaskConfigIdOrderByIdAsc",
                List.of(result(12L, TASK_ID, "formal")), TASK_ID);
        taskRunResults.answer("countLinkedResultsForRunAndTask", 0L, 9L, TASK_ID, List.of(11L));
        assertThrows(IllegalArgumentException.class,
                () -> service.report(TASK_ID, report("batch", "token", List.of(9L, 11L))));

        TaskConfig mutatedResults = batchCodeTask("run", "token", List.of(11L));
        stubTask(mutatedResults);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING")),
                TASK_ID, "BATCH_VALIDATION:run");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", List.of(link(21L, 9L, 11L)), 9L);
        taskResults.answer("findByTaskConfigIdOrderByIdAsc",
                List.of(result(11L, TASK_ID, "formal"), result(12L, TASK_ID, "formal")), TASK_ID);
        taskRunResults.answer("countLinkedResultsForRunAndTask", 1L, 9L, TASK_ID, List.of(11L));
        assertThrows(IllegalArgumentException.class,
                () -> service.report(TASK_ID, report("batch", "token", List.of(9L, 11L))));
    }

    @Test
    void returnsAllSevenNodesWithOnlyCurrentNodeActive() throws Exception {
        TaskConfig task = taskAt(OnboardingStep.BATCH_VALIDATION);
        task.setOnboardingContext(OBJECT_MAPPER.writeValueAsString(new TaskOnboardingContext()));
        stubTask(task);

        TaskOnboardingResponse response = service.get(TASK_ID);

        assertEquals(7, response.getNodes().size());
        assertEquals(List.of("COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED", "ACTIVE", "LOCKED", "LOCKED"),
                response.getNodes().stream().map(node -> node.getState()).toList());
        assertEquals(1, response.getNodes().stream().filter(node -> "ACTIVE".equals(node.getState())).count());
        assertEquals(List.of("CONFIRM_BATCH_VALIDATION"), response.getAllowedActions());
    }

    @Test
    void confirmsResultValidationOnlyFromResultValidationToResultGeneration() {
        TaskConfig task = taskAt(OnboardingStep.RESULT_VALIDATION);
        stubTask(task);

        TaskOnboardingResponse response = service.confirmResultValidation(TASK_ID);

        assertEquals(OnboardingStep.RESULT_GENERATION.name(), task.getOnboardingStep());
        assertEquals(OnboardingStep.RESULT_GENERATION.name(), response.getCurrentStep());

        task.setOnboardingStep(OnboardingStep.BATCH_CODE.name());
        assertThrows(IllegalArgumentException.class, () -> service.confirmResultValidation(TASK_ID));
    }

    @Test
    void confirmsBatchValidationOnlyFromBatchValidationToBatchGeneration() {
        TaskConfig task = taskAt(OnboardingStep.BATCH_VALIDATION);
        stubTask(task);

        TaskOnboardingResponse response = service.confirmBatchValidation(TASK_ID);

        assertEquals(OnboardingStep.BATCH_GENERATION.name(), task.getOnboardingStep());
        assertEquals(OnboardingStep.BATCH_GENERATION.name(), response.getCurrentStep());

        task.setOnboardingStep(OnboardingStep.RESULT_CODE.name());
        assertThrows(IllegalArgumentException.class, () -> service.confirmBatchValidation(TASK_ID));
    }

    @Test
    void getAtCodeStepsCreatesAndPersistsPromptIdentityOnlyOnce() throws Exception {
        TaskConfig resultTask = taskAt(OnboardingStep.RESULT_CODE);
        stubTask(resultTask);
        TaskOnboardingResponse firstResult = service.get(TASK_ID);
        TaskOnboardingContext firstResultContext = context(resultTask);
        String resultRunId = firstResultContext.getResultValidationRunId();
        String resultToken = firstResultContext.getResultReportToken();
        TaskOnboardingResponse secondResult = service.get(TASK_ID);
        TaskOnboardingContext secondResultContext = context(resultTask);

        assertFalse(resultRunId.isBlank());
        assertFalse(resultToken.isBlank());
        assertEquals(resultRunId, secondResultContext.getResultValidationRunId());
        assertEquals(resultToken, secondResultContext.getResultReportToken());
        assertEquals(firstResult.getPrompt(), secondResult.getPrompt());
        assertFalse(firstResult.getPrompt().isBlank());

        TaskConfig batchTask = taskAt(OnboardingStep.BATCH_CODE);
        stubTask(batchTask);
        taskResults.answer("findByTaskConfigIdOrderByIdAsc",
                List.of(result(201L, TASK_ID, "formal"), result(202L, TASK_ID, "formal")), TASK_ID);

        service.get(TASK_ID);
        TaskOnboardingContext firstBatchContext = context(batchTask);
        String batchMarker = firstBatchContext.getBatchValidationMarker();
        String batchToken = firstBatchContext.getBatchReportToken();
        service.get(TASK_ID);
        TaskOnboardingContext secondBatchContext = context(batchTask);

        assertFalse(batchMarker.isBlank());
        assertFalse(batchToken.isBlank());
        assertEquals(batchMarker, secondBatchContext.getBatchValidationMarker());
        assertEquals(batchToken, secondBatchContext.getBatchReportToken());
        assertEquals(List.of(201L, 202L), secondBatchContext.getBatchValidationResultIds());
        assertNotEquals(resultRunId, batchMarker);
        assertEquals(2, taskConfigs.callCount("save"));
    }

    private void assertInvalidResultCallback(TaskConfig task, TaskOnboardingReportRequest request) {
        stubTask(task);
        assertThrows(IllegalArgumentException.class, () -> service.report(TASK_ID, request));
    }

    private void assertInvalidBatch(TaskConfig task, TaskOnboardingReportRequest request) {
        stubTask(task);
        assertThrows(IllegalArgumentException.class, () -> service.report(TASK_ID, request));
    }

    private void assertRejectedBatchRun(TaskRun run) {
        TaskConfig task = batchCodeTask("run", "token", List.of(11L));
        stubTask(task);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run), TASK_ID, "BATCH_VALIDATION:run");
        assertThrows(IllegalArgumentException.class,
                () -> service.report(TASK_ID, report("batch", "token", List.of(9L, 11L))));
    }

    private void stubTask(TaskConfig task) {
        taskConfigs.answer("findById", Optional.of(task), TASK_ID);
    }

    private static TaskConfig taskAt(OnboardingStep step) {
        TaskConfig task = new TaskConfig();
        task.setId(TASK_ID);
        task.setTaskName("Task seven");
        task.setProjectId(3L);
        task.setCliId("codex");
        task.setSelectedTables("[\"word\"]");
        task.setOnboardingStep(step.name());
        task.setOnboardingStatus(OnboardingStatus.ACTIVE.name());
        return task;
    }

    private static TaskConfig resultCodeTask(String runId, String token) {
        TaskConfig task = taskAt(OnboardingStep.RESULT_CODE);
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setResultValidationRunId(runId);
        context.setResultReportToken(token);
        task.setOnboardingContext(write(context));
        return task;
    }

    private static TaskConfig batchCodeTask(String runId, String token, List<Long> baselineResultIds) {
        TaskConfig task = taskAt(OnboardingStep.BATCH_CODE);
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setBatchValidationMarker(runId);
        context.setBatchReportToken(token);
        context.setBatchValidationResultIds(baselineResultIds);
        task.setOnboardingContext(write(context));
        return task;
    }

    private static TaskOnboardingReportRequest report(String stage, String token, List<Long> ids) {
        TaskOnboardingReportRequest request = new TaskOnboardingReportRequest();
        request.setStage(stage);
        request.setToken(token);
        request.setArtifact("src/result-generator.py");
        request.setArtifactHash("sha256-result");
        request.setEntityIds(ids);
        return request;
    }

    private static TaskResult result(Long id, Long taskId, String marker) {
        TaskResult result = new TaskResult();
        result.setId(id);
        result.setTaskConfigId(taskId);
        result.setSourceDescription(marker);
        result.setResultName("result-" + id);
        result.setProjectId(3L);
        return result;
    }

    private static TaskRun run(Long id, Long taskId, String marker, String status) {
        TaskRun run = new TaskRun();
        run.setId(id);
        run.setTaskConfigId(taskId);
        run.setReason(marker);
        run.setStatus(status);
        run.setTaskName("validation run");
        run.setProjectId(3L);
        run.setCliId("codex");
        return run;
    }

    private static TaskRunResult link(Long id, Long runId, Long resultId) {
        TaskRunResult link = new TaskRunResult();
        link.setId(id);
        link.setTaskRunId(runId);
        link.setTaskResultId(resultId);
        return link;
    }

    private static TaskOnboardingContext context(TaskConfig task) throws Exception {
        return OBJECT_MAPPER.readValue(task.getOnboardingContext(), TaskOnboardingContext.class);
    }

    private static String write(TaskOnboardingContext context) {
        try {
            return OBJECT_MAPPER.writeValueAsString(context);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record RepositoryCall(String method, List<Object> arguments) {
    }

    private static final class RepositoryStub<T> implements InvocationHandler {
        private final Class<T> type;
        private final Map<RepositoryCall, Object> answers = new HashMap<>();
        private final List<RepositoryCall> calls = new ArrayList<>();
        private final T proxy;

        @SuppressWarnings("unchecked")
        private RepositoryStub(Class<T> type) {
            this.type = type;
            this.proxy = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, this);
        }

        private T proxy() {
            return proxy;
        }

        private void answer(String method, Object value, Object... arguments) {
            answers.put(new RepositoryCall(method, List.of(arguments)), value);
        }

        private long callCount(String method) {
            return calls.stream().filter(call -> method.equals(call.method())).count();
        }

        @Override
        public Object invoke(Object ignored, Method method, Object[] arguments) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "RepositoryStub<" + type.getSimpleName() + ">";
                    case "hashCode" -> System.identityHashCode(this);
                    case "equals" -> ignored == arguments[0];
                    default -> null;
                };
            }
            RepositoryCall call = new RepositoryCall(
                    method.getName(), arguments == null ? List.of() : List.of(arguments));
            calls.add(call);
            if (answers.containsKey(call)) {
                return answers.get(call);
            }
            if (method.getName().equals("save") || method.getName().equals("saveAll")) {
                return arguments[0];
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == long.class || returnType == int.class || returnType == short.class) {
                return 0;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (Optional.class.isAssignableFrom(returnType)) {
                return Optional.empty();
            }
            if (Iterable.class.isAssignableFrom(returnType)) {
                return List.of();
            }
            return null;
        }
    }
}
