package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.dto.TaskOnboardingReportRequest;
import com.aitaskcenter.dto.TaskOnboardingResponse;
import com.aitaskcenter.dto.GenerateTaskRunBatchRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.model.TaskRunResult;
import com.aitaskcenter.repository.ConnectionConfigRepository;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import com.aitaskcenter.service.PythonWorkerClient;
import com.aitaskcenter.service.TaskConfigService;
import com.aitaskcenter.service.TaskRunPromptBuilder;
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
import java.util.Set;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskOnboardingServiceTest {
    private static final Long TASK_ID = 7L;
    private static final String GENERATION_ID = "b".repeat(64);
    private static final String ARTIFACT_HASH = "a".repeat(64);
    private static final String EMPTY_FINGERPRINT =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
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
    private TaskOnboardingCleanupService cleanupService;
    private TaskConfigService taskConfigService;

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
        cleanupService = mock(TaskOnboardingCleanupService.class);
        taskConfigService = mock(TaskConfigService.class);
        TaskOnboardingContextCodec contextCodec = new TaskOnboardingContextCodec(OBJECT_MAPPER);
        TaskOnboardingSnapshotService snapshotService = new TaskOnboardingSnapshotService(
                taskResultRepository, taskRunRepository, taskRunResultRepository);
        TaskOnboardingCallbackValidator callbackValidator = new TaskOnboardingCallbackValidator(
                taskResultRepository,
                taskRunRepository,
                taskRunResultRepository,
                OBJECT_MAPPER,
                snapshotService);
        TaskOnboardingResponseAssembler responseAssembler = new TaskOnboardingResponseAssembler(
                taskResultRepository, taskRunRepository, promptBuilder);
        service = new TaskOnboardingService(
                taskConfigRepository,
                contextCodec,
                snapshotService,
                callbackValidator,
                responseAssembler,
                () -> { },
                cleanupService,
                taskConfigService);
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
        assertEquals(ARTIFACT_HASH, savedContext.getResultArtifactHash());
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

        assertRejectedWithoutMutation(task, report("result", "result-token", List.of(101L)));
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
        assertRejectedWithoutMutation(task, request);
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
        assertRejectedWithoutMutation(duplicateIds, report("result", "token", List.of(1L, 1L)));

        TaskConfig mismatchedIds = resultCodeTask("run", "token");
        stubTask(mismatchedIds);
        taskResults.answer("findByTaskConfigIdAndSourceDescriptionOrderByIdAsc",
                List.of(result(1L, TASK_ID, "RESULT_VALIDATION:run")),
                TASK_ID, "RESULT_VALIDATION:run");
        assertRejectedWithoutMutation(mismatchedIds, report("result", "token", List.of(2L)));

        TaskConfig wrongMarkerOrTask = resultCodeTask("run", "token");
        stubTask(wrongMarkerOrTask);
        taskResults.answer("findByTaskConfigIdAndSourceDescriptionOrderByIdAsc",
                List.of(result(1L, 999L, "RESULT_VALIDATION:other")),
                TASK_ID, "RESULT_VALIDATION:run");
        assertRejectedWithoutMutation(wrongMarkerOrTask, report("result", "token", List.of(1L)));
    }

    @Test
    void rejectsResultRowsWithMissingMalformedOrMismatchedValidationMetadata() {
        assertRejectedResultMetadata(null);
        assertRejectedResultMetadata("not-json");
        assertRejectedResultMetadata("{}");
        assertRejectedResultMetadata("{\"_meta\":{}}");
        assertRejectedResultMetadata("{\"_meta\":{\"validationRunId\":\"RESULT_VALIDATION:other\"}}");
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
    void acceptsBatchWhenRunIdEqualsAResultId() throws Exception {
        TaskConfig task = batchCodeTask("run", "token", List.of(9L));
        TaskRun run = run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING");
        stubTask(task);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run), TASK_ID, "BATCH_VALIDATION:run");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", List.of(link(21L, 9L, 9L)), 9L);
        taskRunResults.answer("countLinkedResultsForRunAndTask", 1L, 9L, TASK_ID, List.of(9L));

        service.report(TASK_ID, report("batch", "token", List.of(9L, 9L)));

        TaskOnboardingContext savedContext = context(task);
        assertEquals(9L, savedContext.getBatchValidationTaskRunId());
        assertEquals(List.of(9L), savedContext.getBatchValidationResultIds());
    }

    @Test
    void acceptsBatchLinksRegardlessOfLinkInsertionOrderAndStoresAscendingResultIds() throws Exception {
        TaskConfig task = batchCodeTask("run", "token", List.of(11L, 12L));
        TaskRun run = run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING");
        stubTask(task);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run), TASK_ID, "BATCH_VALIDATION:run");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc",
                List.of(link(21L, 9L, 12L), link(22L, 9L, 11L)), 9L);
        taskRunResults.answer("countLinkedResultsForRunAndTask", 2L, 9L, TASK_ID, List.of(11L, 12L));

        service.report(TASK_ID, report("batch", "token", List.of(9L, 12L, 11L)));

        assertEquals(List.of(11L, 12L), context(task).getBatchValidationResultIds());
    }

    @Test
    void rejectsBatchWithInvalidRunMarkerTaskStateOrRelationships() {
        assertInvalidBatch(batchCodeTask("run", "token", List.of(11L)), report("batch", "token", List.of()));
        assertInvalidBatch(batchCodeTask("run", "token", List.of(11L)), report("batch", "token", List.of(9L)));

        TaskConfig noRun = batchCodeTask("run", "token", List.of(11L));
        stubTask(noRun);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc", List.of(), TASK_ID, "BATCH_VALIDATION:run");
        assertRejectedWithoutMutation(noRun, report("batch", "token", List.of(9L, 11L)));

        TaskConfig wrongCount = batchCodeTask("run", "token", List.of(11L));
        stubTask(wrongCount);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc", List.of(
                run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING"),
                run(10L, TASK_ID, "BATCH_VALIDATION:run", "PENDING")), TASK_ID, "BATCH_VALIDATION:run");
        assertRejectedWithoutMutation(wrongCount, report("batch", "token", List.of(9L, 11L)));

        TaskConfig wrongRunId = batchCodeTask("run", "token", List.of(11L));
        stubTask(wrongRunId);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING")),
                TASK_ID, "BATCH_VALIDATION:run");
        assertRejectedWithoutMutation(wrongRunId, report("batch", "token", List.of(10L, 11L)));

        assertRejectedBatchRun(run(9L, TASK_ID, "BATCH_VALIDATION:run", "RUNNING"));
        TaskRun started = run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING");
        started.setStartTime(OffsetDateTime.now());
        assertRejectedBatchRun(started);
        TaskRun ended = run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING");
        ended.setEndTime(OffsetDateTime.now());
        assertRejectedBatchRun(ended);
        assertRejectedBatchRun(run(9L, TASK_ID, "BATCH_VALIDATION:other", "PENDING"));
        assertRejectedBatchRun(run(9L, 999L, "BATCH_VALIDATION:run", "PENDING"));

        TaskConfig linkMismatch = batchCodeTask("run", "token", List.of(11L));
        stubTask(linkMismatch);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING")),
                TASK_ID, "BATCH_VALIDATION:run");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", List.of(link(21L, 9L, 12L)), 9L);
        assertRejectedWithoutMutation(linkMismatch, report("batch", "token", List.of(9L, 11L)));

        TaskConfig otherTaskResult = batchCodeTask("run", "token", List.of(11L));
        stubTask(otherTaskResult);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING")),
                TASK_ID, "BATCH_VALIDATION:run");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", List.of(link(21L, 9L, 11L)), 9L);
        taskResults.answer("findByTaskConfigIdOrderByIdAsc",
                List.of(result(12L, TASK_ID, "formal")), TASK_ID);
        taskRunResults.answer("countLinkedResultsForRunAndTask", 0L, 9L, TASK_ID, List.of(11L));
        assertRejectedWithoutMutation(otherTaskResult, report("batch", "token", List.of(9L, 11L)));

        TaskConfig mutatedResults = batchCodeTask("run", "token", List.of(11L));
        stubTask(mutatedResults);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING")),
                TASK_ID, "BATCH_VALIDATION:run");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", List.of(link(21L, 9L, 11L)), 9L);
        taskResults.answer("findFingerprintRowsByTaskConfigId", List.of("modified-result"), TASK_ID);
        taskRunResults.answer("countLinkedResultsForRunAndTask", 1L, 9L, TASK_ID, List.of(11L));
        assertRejectedWithoutMutation(mutatedResults, report("batch", "token", List.of(9L, 11L)));
    }

    @Test
    void rejectsBatchCallbackForWrongStageTokenStepDuplicateIdsAndConsumedToken() {
        assertInvalidBatch(batchCodeTask("run", "token", List.of(11L)),
                report("result", "token", List.of(9L, 11L)));
        assertInvalidBatch(batchCodeTask("run", "token", List.of(11L)),
                report("batch", "wrong", List.of(9L, 11L)));
        assertInvalidBatch(batchCodeTask("run", "", List.of(11L)),
                report("batch", "used-token", List.of(9L, 11L)));
        assertInvalidBatch(batchCodeTask("run", "token", List.of(11L)),
                report("batch", "token", List.of(9L, 11L, 11L)));

        TaskConfig wrongStep = batchCodeTask("run", "token", List.of(11L));
        wrongStep.setOnboardingStep(OnboardingStep.BATCH_VALIDATION.name());
        assertInvalidBatch(wrongStep, report("batch", "token", List.of(9L, 11L)));
    }

    @Test
    void rejectsReusedBatchCallbackTokenAfterSuccessfulConsumption() throws Exception {
        TaskConfig task = batchCodeTask("run", "token", List.of(11L));
        TaskRun run = run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING");
        TaskOnboardingReportRequest request = report("batch", "token", List.of(9L, 11L));
        stubTask(task);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run), TASK_ID, "BATCH_VALIDATION:run");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", List.of(link(21L, 9L, 11L)), 9L);
        taskResults.answer("findByTaskConfigIdOrderByIdAsc",
                List.of(result(11L, TASK_ID, "formal")), TASK_ID);
        taskRunResults.answer("countLinkedResultsForRunAndTask", 1L, 9L, TASK_ID, List.of(11L));

        service.report(TASK_ID, request);

        assertEquals("", context(task).getBatchReportToken());
        task.setOnboardingStep(OnboardingStep.BATCH_CODE.name());
        assertRejectedWithoutMutation(task, request);
    }

    @Test
    void rejectsBatchRunWithMissingMalformedOrMismatchedValidationMetadata() {
        assertRejectedBatchMetadata(null);
        assertRejectedBatchMetadata("not-json");
        assertRejectedBatchMetadata("{}");
        assertRejectedBatchMetadata("{\"_meta\":{}}");
        assertRejectedBatchMetadata("{\"_meta\":{\"validationRunId\":\"BATCH_VALIDATION:other\"}}");
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
        taskResults.answer("findFingerprintRowsByTaskConfigId",
                List.of("formal-201", "formal-202"), TASK_ID);

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
        assertEquals(List.of(), secondBatchContext.getBatchValidationResultIds());
        assertEquals(2L, secondBatchContext.getBaselineResultCount());
        assertNotEquals(resultRunId, batchMarker);
        assertEquals(2, taskConfigs.callCount("save"));
    }

    @Test
    void repositoryDeclaresPessimisticWriteLockForOnboardingMutations() throws Exception {
        Method method = TaskConfigRepository.class.getMethod("findByIdForUpdate", Long.class);

        Lock lock = method.getAnnotation(Lock.class);

        assertNotNull(lock);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    @Test
    void publicOperationsLoadTaskThroughWriteLock() {
        TaskConfig task = taskAt(OnboardingStep.READY);
        stubTaskForUpdate(task);

        service.get(TASK_ID);

        assertEquals(1, taskConfigs.callCount("findByIdForUpdate"));
        assertEquals(0, taskConfigs.callCount("findById"));
    }

    @Test
    void nonActiveCurrentStateHasMatchingNodeNoActionsAndCannotMutate() {
        for (OnboardingStatus status : List.of(
                OnboardingStatus.FAILED, OnboardingStatus.STALE, OnboardingStatus.COMPLETED)) {
            TaskConfig task = resultCodeTask("run", "token");
            task.setOnboardingStatus(status.name());
            stubTask(task);

            TaskOnboardingResponse response = service.get(TASK_ID);

            assertEquals(status.name(), response.getNodes().get(0).getState());
            assertEquals(List.of(), response.getAllowedActions());
            assertNull(response.getPrompt());
            assertEquals(0, taskConfigs.callCount("save"));
            assertRejectedWithoutMutation(task, report("result", "token", List.of(1L)));
        }

        TaskConfig confirmTask = taskAt(OnboardingStep.RESULT_VALIDATION);
        confirmTask.setOnboardingStatus(OnboardingStatus.FAILED.name());
        stubTask(confirmTask);
        String context = confirmTask.getOnboardingContext();
        assertThrows(IllegalStateException.class, () -> service.confirmResultValidation(TASK_ID));
        assertEquals(OnboardingStep.RESULT_VALIDATION.name(), confirmTask.getOnboardingStep());
        assertEquals(OnboardingStatus.FAILED.name(), confirmTask.getOnboardingStatus());
        assertEquals(context, confirmTask.getOnboardingContext());
    }

    @Test
    void rejectsNullNullListsAndSemanticallyInconsistentContextCleanly() {
        TaskConfig nullContext = taskAt(OnboardingStep.READY);
        nullContext.setOnboardingContext("null");
        stubTask(nullContext);
        assertControlledContextFailure(nullContext);

        TaskConfig nullLists = taskAt(OnboardingStep.READY);
        nullLists.setOnboardingContext("{\"resultValidationIds\":null}");
        stubTask(nullLists);
        assertControlledContextFailure(nullLists);

        TaskConfig partialIdentity = resultCodeTask("", "token");
        stubTask(partialIdentity);
        assertControlledContextFailure(partialIdentity);

        TaskConfig partialBaseline = taskAt(OnboardingStep.READY);
        TaskOnboardingContext corruptBaseline = new TaskOnboardingContext();
        corruptBaseline.setBaselineStage("RESULT");
        corruptBaseline.setBaselineResultCount(0);
        partialBaseline.setOnboardingContext(write(corruptBaseline));
        stubTask(partialBaseline);
        assertControlledContextFailure(partialBaseline);
    }

    @Test
    void rejectsBlankArtifactAndNonSha256ArtifactHashWithoutConsumingToken() {
        TaskConfig blankArtifact = resultCodeTask("run", "token");
        TaskOnboardingReportRequest blankArtifactRequest = report("result", "token", List.of(1L));
        blankArtifactRequest.setArtifact("  ");
        assertInvalidProvenance(blankArtifact, blankArtifactRequest);

        TaskConfig uppercaseHash = resultCodeTask("run", "token");
        TaskOnboardingReportRequest uppercaseHashRequest = report("result", "token", List.of(1L));
        uppercaseHashRequest.setArtifactHash("A".repeat(64));
        assertInvalidProvenance(uppercaseHash, uppercaseHashRequest);

        TaskConfig shortHash = resultCodeTask("run", "token");
        TaskOnboardingReportRequest shortHashRequest = report("result", "token", List.of(1L));
        shortHashRequest.setArtifactHash("abc123");
        assertInvalidProvenance(shortHash, shortHashRequest);
    }

    @Test
    void responseSerializationOmitsEntityAndContextSecrets() throws Exception {
        TaskConfig task = taskAt(OnboardingStep.BATCH_VALIDATION);
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setResultValidationRunId("secret-result-run");
        context.setResultReportToken("secret-report-token");
        context.setBatchValidationTaskRunId(9L);
        context.setBatchValidationMarker("run");
        context.setBatchValidationResultIds(List.of(11L));
        task.setOnboardingContext(write(context));
        TaskRun run = run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING");
        run.setClaimToken("secret-claim-token");
        run.setWorkerId("secret-worker");
        run.setAiResponseJson("secret-ai-response");
        run.setRunLog("secret-run-log");
        stubTask(task);
        taskRuns.answer("findByIdAndTaskConfigIdAndReason",
                Optional.of(run), 9L, TASK_ID, "BATCH_VALIDATION:run");

        String json = OBJECT_MAPPER.writeValueAsString(service.get(TASK_ID));

        assertFalse(json.contains("onboardingContext"));
        assertFalse(json.contains("secret-report-token"));
        assertFalse(json.contains("claimToken"));
        assertFalse(json.contains("secret-claim-token"));
        assertFalse(json.contains("secret-worker"));
        assertFalse(json.contains("secret-ai-response"));
        assertFalse(json.contains("secret-run-log"));
    }

    @Test
    void codeStepGetPersistsCompactDeterministicBaselineFingerprintsOnlyOnce() throws Exception {
        TaskConfig task = taskAt(OnboardingStep.RESULT_CODE);
        stubTask(task);
        taskResults.answer("findFingerprintRowsByTaskConfigId", List.of("result-a", "result-b"), TASK_ID);
        taskRuns.answer("findFingerprintRowsByTaskConfigId", List.of("run-a"), TASK_ID);
        taskRunResults.answer("findFingerprintRowsByTaskConfigId", List.of("link-a", "link-b"), TASK_ID);

        service.get(TASK_ID);
        TaskOnboardingContext first = context(task);
        service.get(TASK_ID);
        TaskOnboardingContext second = context(task);

        assertEquals("RESULT", first.getBaselineStage());
        assertEquals(2L, first.getBaselineResultCount());
        assertEquals(1L, first.getBaselineRunCount());
        assertEquals(2L, first.getBaselineLinkCount());
        assertTrue(first.getBaselineResultFingerprint().matches("[0-9a-f]{64}"));
        assertTrue(first.getBaselineRunFingerprint().matches("[0-9a-f]{64}"));
        assertTrue(first.getBaselineLinkFingerprint().matches("[0-9a-f]{64}"));
        assertEquals(first.getBaselineResultFingerprint(), second.getBaselineResultFingerprint());
        assertEquals(1, taskResults.callCount("findFingerprintRowsByTaskConfigId"));
        assertEquals(1, taskRuns.callCount("findFingerprintRowsByTaskConfigId"));
        assertEquals(1, taskRunResults.callCount("findFingerprintRowsByTaskConfigId"));
    }

    @Test
    void resultCallbackRejectsExtraUnmarkedModifiedDeletedRowsExtraRunsAndExtraLinks() {
        assertForbiddenResultSideEffect(
                List.of("old-result"), List.of("old-result", "extra-unmarked"),
                List.of("old-run"), List.of("old-run"),
                List.of("old-link"), List.of("old-link"));
        assertForbiddenResultSideEffect(
                List.of("old-result"), List.of("modified-result"),
                List.of("old-run"), List.of("old-run"),
                List.of("old-link"), List.of("old-link"));
        assertForbiddenResultSideEffect(
                List.of("old-result"), List.of(),
                List.of("old-run"), List.of("old-run"),
                List.of("old-link"), List.of("old-link"));
        assertForbiddenResultSideEffect(
                List.of("old-result"), List.of("old-result"),
                List.of("old-run"), List.of("old-run", "extra-run"),
                List.of("old-link"), List.of("old-link"));
        assertForbiddenResultSideEffect(
                List.of("old-result"), List.of("old-result"),
                List.of("old-run"), List.of("old-run"),
                List.of("old-link"), List.of("old-link", "extra-link"));
    }

    @Test
    void batchCallbackRejectsModifiedResultsExtraRunsAndExtraLinks() {
        assertForbiddenBatchSideEffect(
                List.of("old-result"), List.of("modified-result"),
                List.of("old-run"), List.of("old-run"),
                List.of("old-link"), List.of("old-link"));
        assertForbiddenBatchSideEffect(
                List.of("old-result"), List.of("old-result"),
                List.of("old-run"), List.of("old-run", "extra-run"),
                List.of("old-link"), List.of("old-link"));
        assertForbiddenBatchSideEffect(
                List.of("old-result"), List.of("old-result"),
                List.of("old-run"), List.of("old-run"),
                List.of("old-link"), List.of("old-link", "extra-link"));
    }

    @Test
    void failedCleanupDoesNotCallFormalGeneration() throws Exception {
        TaskConfig task = resultGenerationTask(GENERATION_ID, List.of(101L));
        stubTask(task);
        when(cleanupService.deleteResultValidation(TASK_ID, GENERATION_ID))
                .thenThrow(new IllegalStateException("validation row is linked"));

        assertThrows(IllegalStateException.class, () -> service.generateResults(TASK_ID));

        verifyNoInteractions(taskConfigService);
        assertEquals(OnboardingStep.RESULT_GENERATION.name(), task.getOnboardingStep());
        assertEquals(List.of(101L), context(task).getResultValidationIds());
    }

    @Test
    void successfulResultGenerationAdvancesOnlyToBatchCode() throws Exception {
        TaskConfig task = resultGenerationTask(GENERATION_ID, List.of(101L));
        stubTask(task);
        when(cleanupService.deleteResultValidation(TASK_ID, GENERATION_ID)).thenReturn(1);
        when(taskConfigService.generateResults(TASK_ID, false, GENERATION_ID))
                .thenReturn(Map.of("insertedCount", 4));
        taskResults.answer("findFingerprintRowsByTaskConfigId", List.of("formal-result"), TASK_ID);

        TaskOnboardingResponse response = service.generateResults(TASK_ID);

        TaskOnboardingContext nextContext = context(task);
        assertEquals(OnboardingStep.BATCH_CODE.name(), task.getOnboardingStep());
        assertEquals(OnboardingStatus.ACTIVE.name(), task.getOnboardingStatus());
        assertEquals(OnboardingStep.BATCH_CODE.name(), response.getCurrentStep());
        assertEquals("", nextContext.getResultValidationRunId());
        assertEquals(List.of(), nextContext.getResultValidationIds());
        assertFalse(nextContext.getBatchValidationMarker().isBlank());
        assertFalse(nextContext.getBatchReportToken().isBlank());
        assertEquals("BATCH", nextContext.getBaselineStage());
        verify(taskConfigService).generateResults(TASK_ID, false, GENERATION_ID);
    }

    @Test
    void zeroResultInsertCountDoesNotAdvance() {
        TaskConfig task = resultGenerationTask(GENERATION_ID, List.of(101L));
        stubTask(task);
        when(cleanupService.deleteResultValidation(TASK_ID, GENERATION_ID)).thenReturn(1);
        when(taskConfigService.generateResults(TASK_ID, false, GENERATION_ID))
                .thenReturn(Map.of("insertedCount", 0));

        assertThrows(TaskOnboardingStateException.class, () -> service.generateResults(TASK_ID));
        assertEquals(OnboardingStep.RESULT_GENERATION.name(), task.getOnboardingStep());
    }

    @Test
    void successfulBatchGenerationAdvancesReadyOnlyWhenBothCountsArePositive() {
        TaskConfig task = batchGenerationTask("batch-run-1", 301L, List.of(201L));
        GenerateTaskRunBatchRequest request = new GenerateTaskRunBatchRequest();
        stubTask(task);
        when(cleanupService.deleteBatchValidation(TASK_ID, 301L, "batch-run-1")).thenReturn(1);
        when(taskConfigService.generateRunBatches(
                TASK_ID, request, "batch-run-1", List.of(201L))).thenReturn(Map.of(
                "createdRunCount", 1,
                "linkedResultCount", 1));

        TaskOnboardingResponse response = service.generateBatches(TASK_ID, request);

        assertEquals(OnboardingStep.READY.name(), task.getOnboardingStep());
        assertEquals(OnboardingStep.READY.name(), response.getCurrentStep());
        verify(taskConfigService).generateRunBatches(
                TASK_ID, request, "batch-run-1", List.of(201L));
    }

    @Test
    void retryAfterLostResultResponseUsesSameGenerationIdAndAcceptsRecoveredCount() throws Exception {
        TaskConfig task = resultGenerationTask(GENERATION_ID, List.of(101L));
        stubTask(task);
        when(cleanupService.deleteResultValidation(TASK_ID, GENERATION_ID)).thenReturn(1);
        when(taskConfigService.generateResults(TASK_ID, false, GENERATION_ID))
                .thenThrow(new IllegalArgumentException("response lost after commit"))
                .thenReturn(Map.of("insertedCount", 4, "recovered", true));

        assertThrows(TaskOnboardingStateException.class, () -> service.generateResults(TASK_ID));

        assertEquals(OnboardingStep.RESULT_GENERATION.name(), task.getOnboardingStep());
        assertEquals(OnboardingStatus.FAILED.name(), task.getOnboardingStatus());
        assertEquals(GENERATION_ID, context(task).getResultValidationRunId());
        assertEquals(List.of("GENERATE_RESULTS"), service.get(TASK_ID).getAllowedActions());

        TaskOnboardingResponse recovered = service.generateResults(TASK_ID);

        assertEquals(OnboardingStep.BATCH_CODE.name(), recovered.getCurrentStep());
        verify(taskConfigService, times(2)).generateResults(TASK_ID, false, GENERATION_ID);
    }

    @Test
    void successfulTerminalGenerationRetriesAreNoOps() {
        TaskOnboardingContext resultContext = new TaskOnboardingContext();
        resultContext.setCompletedResultGenerationId(GENERATION_ID);
        resultContext.setCompletedResultCount(2);
        resultContext.setBatchValidationMarker("b".repeat(64));
        resultContext.setBatchReportToken("token");
        setEmptyBaseline(resultContext, "BATCH");
        TaskConfig afterResults = taskAt(OnboardingStep.BATCH_CODE);
        afterResults.setOnboardingContext(write(resultContext));
        stubTask(afterResults);

        assertEquals(OnboardingStep.BATCH_CODE.name(), service.generateResults(TASK_ID).getCurrentStep());
        verifyNoInteractions(cleanupService, taskConfigService);

        reset(cleanupService, taskConfigService);
        TaskOnboardingContext batchContext = new TaskOnboardingContext();
        batchContext.setCompletedResultGenerationId(GENERATION_ID);
        batchContext.setCompletedResultCount(2);
        batchContext.setCompletedBatchGenerationId("c".repeat(64));
        batchContext.setCompletedBatchRunCount(1);
        batchContext.setCompletedBatchLinkCount(2);
        TaskConfig ready = taskAt(OnboardingStep.READY);
        ready.setOnboardingContext(write(batchContext));
        stubTask(ready);

        assertEquals(OnboardingStep.READY.name(), service.generateBatches(
                TASK_ID, new GenerateTaskRunBatchRequest()).getCurrentStep());
        assertEquals(OnboardingStep.READY.name(), service.generateResults(TASK_ID).getCurrentStep());
        verifyNoInteractions(cleanupService, taskConfigService);
    }

    @Test
    void generationOrchestrationSuspendsAnyCallerTransaction() throws Exception {
        Method resultMethod = TaskOnboardingService.class.getMethod("generateResults", Long.class);
        Method batchMethod = TaskOnboardingService.class.getMethod(
                "generateBatches", Long.class, GenerateTaskRunBatchRequest.class);

        assertEquals(Propagation.NOT_SUPPORTED,
                resultMethod.getAnnotation(Transactional.class).propagation());
        assertEquals(Propagation.NOT_SUPPORTED,
                batchMethod.getAnnotation(Transactional.class).propagation());
    }

    @Test
    void zeroOrPartialBatchCountsDoNotAdvance() {
        List<Map<String, Object>> incompleteCounts = List.of(
                Map.of("createdRunCount", 0, "linkedResultCount", 1),
                Map.of("createdRunCount", 1, "linkedResultCount", 0),
                Map.of("createdRunCount", 0, "linkedResultCount", 0));

        for (Map<String, Object> counts : incompleteCounts) {
            reset(cleanupService, taskConfigService);
            TaskConfig task = batchGenerationTask("batch-run-1", 301L, List.of(201L));
            GenerateTaskRunBatchRequest request = new GenerateTaskRunBatchRequest();
            stubTask(task);
            when(cleanupService.deleteBatchValidation(TASK_ID, 301L, "batch-run-1")).thenReturn(1);
            when(taskConfigService.generateRunBatches(
                    TASK_ID, request, "batch-run-1", List.of(201L))).thenReturn(counts);

            assertThrows(TaskOnboardingStateException.class,
                    () -> service.generateBatches(TASK_ID, request));
            assertEquals(OnboardingStep.BATCH_GENERATION.name(), task.getOnboardingStep());
        }
    }

    @Test
    void wrongStepOrStatusRejectsWithNoCleanupOrGeneration() {
        TaskConfig wrongStep = taskAt(OnboardingStep.RESULT_VALIDATION);
        stubTask(wrongStep);
        assertThrows(RuntimeException.class, () -> service.generateResults(TASK_ID));

        TaskConfig wrongStatus = batchGenerationTask("batch-run-1", 301L, List.of(201L));
        wrongStatus.setOnboardingStatus(OnboardingStatus.COMPLETED.name());
        stubTask(wrongStatus);
        assertThrows(RuntimeException.class,
                () -> service.generateBatches(TASK_ID, new GenerateTaskRunBatchRequest()));

        verifyNoInteractions(cleanupService, taskConfigService);
    }

    @Test
    void initialWorkflowRejectsBothFormalGenerationOperations() {
        TaskConfig initial = taskAt(OnboardingStep.RESULT_CODE);
        stubTask(initial);

        assertThrows(TaskOnboardingStateException.class, () -> service.generateResults(TASK_ID));
        assertThrows(TaskOnboardingStateException.class,
                () -> service.generateBatches(TASK_ID, new GenerateTaskRunBatchRequest()));

        verifyNoInteractions(cleanupService, taskConfigService);
    }

    @Test
    void lockedWorkflowRejectsBothFormalGenerationOperations() {
        TaskConfig lockedResult = resultGenerationTask(GENERATION_ID, List.of(101L));
        lockedResult.setOnboardingStatus(OnboardingStatus.COMPLETED.name());
        stubTask(lockedResult);

        assertThrows(TaskOnboardingStateException.class, () -> service.generateResults(TASK_ID));

        TaskConfig lockedBatch = batchGenerationTask("batch-run-1", 301L, List.of(201L));
        lockedBatch.setOnboardingStatus(OnboardingStatus.COMPLETED.name());
        stubTask(lockedBatch);

        assertThrows(TaskOnboardingStateException.class,
                () -> service.generateBatches(TASK_ID, new GenerateTaskRunBatchRequest()));

        verifyNoInteractions(cleanupService, taskConfigService);
    }

    @Test
    void semanticConfigChangesResetOnboardingAndTaskNameOnlyChangeDoesNot() {
        TaskConfigRepository repository = mock(TaskConfigRepository.class);
        ProjectConfigRepository projects = mock(ProjectConfigRepository.class);
        ConnectionConfigRepository connections = mock(ConnectionConfigRepository.class);
        TaskConfigService configService = new TaskConfigService(
                repository,
                projects,
                connections,
                mock(TaskResultRepository.class),
                mock(PythonWorkerClient.class),
                mock(TaskRunPromptBuilder.class),
                mock(JdbcTemplate.class),
                OBJECT_MAPPER);
        TaskConfig existing = taskAt(OnboardingStep.BATCH_VALIDATION);
        existing.setDatabaseConfigId(5L);
        existing.setTaskDesc("original description");
        existing.setOnboardingStatus(OnboardingStatus.COMPLETED.name());
        existing.setOnboardingContext("{\"preserved\":true}");
        when(repository.findById(TASK_ID)).thenReturn(Optional.of(existing));
        when(projects.existsById(3L)).thenReturn(true);
        when(projects.existsById(4L)).thenReturn(true);
        when(connections.existsById(5L)).thenReturn(true);
        when(connections.existsById(6L)).thenReturn(true);

        TaskConfig nameOnly = configInput("Renamed task", "original description", " [ \"word\" ] ");
        configService.update(TASK_ID, nameOnly);

        assertEquals(OnboardingStep.BATCH_VALIDATION.name(), existing.getOnboardingStep());
        assertEquals(OnboardingStatus.COMPLETED.name(), existing.getOnboardingStatus());
        assertEquals("{\"preserved\":true}", existing.getOnboardingContext());

        List<TaskConfig> semanticChanges = new ArrayList<>();
        TaskConfig projectChange = configInput("Renamed task", "original description", "[\"word\"]");
        projectChange.setProjectId(4L);
        semanticChanges.add(projectChange);
        TaskConfig cliChange = configInput("Renamed task", "original description", "[\"word\"]");
        cliChange.setCliId("other-cli");
        semanticChanges.add(cliChange);
        TaskConfig databaseChange = configInput("Renamed task", "original description", "[\"word\"]");
        databaseChange.setDatabaseConfigId(6L);
        semanticChanges.add(databaseChange);
        semanticChanges.add(configInput("Renamed task", "original description", "[\"other\"]"));
        semanticChanges.add(configInput("Renamed task", "changed description", "[\"word\"]"));

        for (TaskConfig semanticChange : semanticChanges) {
            existing.setProjectId(3L);
            existing.setCliId("codex");
            existing.setDatabaseConfigId(5L);
            existing.setSelectedTables("[\"word\"]");
            existing.setTaskDesc("original description");
            existing.setOnboardingStep(OnboardingStep.BATCH_VALIDATION.name());
            existing.setOnboardingStatus(OnboardingStatus.COMPLETED.name());
            existing.setOnboardingContext("{\"preserved\":true}");

            configService.update(TASK_ID, semanticChange);

            assertEquals(OnboardingStep.RESULT_CODE.name(), existing.getOnboardingStep());
            assertEquals(OnboardingStatus.ACTIVE.name(), existing.getOnboardingStatus());
            assertEquals("{}", existing.getOnboardingContext());
        }
    }

    @Test
    void taskConfigServiceValidatesGenerationIdAndKeepsLegacyCallerCompatible() {
        TaskConfigRepository repository = mock(TaskConfigRepository.class);
        ConnectionConfigRepository connections = mock(ConnectionConfigRepository.class);
        PythonWorkerClient worker = mock(PythonWorkerClient.class);
        TaskConfig task = taskAt(OnboardingStep.RESULT_GENERATION);
        task.setDatabaseConfigId(5L);
        when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(connections.existsById(5L)).thenReturn(true);
        when(worker.generateTaskResults(TASK_ID, false, null))
                .thenReturn(Map.of("insertedCount", 1));
        TaskConfigService configService = new TaskConfigService(
                repository,
                mock(ProjectConfigRepository.class),
                connections,
                mock(TaskResultRepository.class),
                worker,
                mock(TaskRunPromptBuilder.class),
                mock(JdbcTemplate.class),
                OBJECT_MAPPER);

        assertThrows(IllegalArgumentException.class,
                () -> configService.generateResults(TASK_ID, false, "reused-artifact-hash"));
        verify(worker, never()).generateTaskResults(TASK_ID, false, "reused-artifact-hash");

        assertEquals(1, configService.generateResults(TASK_ID, false).get("insertedCount"));
        verify(worker).generateTaskResults(TASK_ID, false, null);
    }

    @Test
    void batchGenerationUsesTransactionalJavaPathWithoutWorkerCall() throws Exception {
        TaskConfigRepository repository = mock(TaskConfigRepository.class);
        TaskResultRepository results = mock(TaskResultRepository.class);
        PythonWorkerClient worker = mock(PythonWorkerClient.class);
        TaskConfig task = taskAt(OnboardingStep.BATCH_GENERATION);
        when(repository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(results.findByTaskConfigIdAndStatusInOrderByIdAsc(TASK_ID, Set.of("PENDING")))
                .thenReturn(List.of());
        TaskConfigService configService = new TaskConfigService(
                repository,
                mock(ProjectConfigRepository.class),
                mock(ConnectionConfigRepository.class),
                results,
                worker,
                mock(TaskRunPromptBuilder.class),
                mock(JdbcTemplate.class),
                OBJECT_MAPPER);

        Map<String, Object> response = configService.generateRunBatches(
                TASK_ID, new GenerateTaskRunBatchRequest());

        assertEquals(0, response.get("createdRunCount"));
        verifyNoInteractions(worker);
        Method method = TaskConfigService.class.getMethod(
                "generateRunBatches", Long.class, GenerateTaskRunBatchRequest.class);
        assertNotNull(method.getAnnotation(Transactional.class));
    }

    private void assertInvalidResultCallback(TaskConfig task, TaskOnboardingReportRequest request) {
        stubTask(task);
        assertRejectedWithoutMutation(task, request);
    }

    private void assertForbiddenResultSideEffect(
            List<String> baselineResults,
            List<String> currentProtectedResults,
            List<String> baselineRuns,
            List<String> currentRuns,
            List<String> baselineLinks,
            List<String> currentLinks) {
        TaskConfig task = taskAt(OnboardingStep.RESULT_CODE);
        stubTask(task);
        taskResults.answer("findFingerprintRowsByTaskConfigId", baselineResults, TASK_ID);
        taskRuns.answer("findFingerprintRowsByTaskConfigId", baselineRuns, TASK_ID);
        taskRunResults.answer("findFingerprintRowsByTaskConfigId", baselineLinks, TASK_ID);
        service.get(TASK_ID);
        TaskOnboardingContext baseline = readContext(task);
        String marker = "RESULT_VALIDATION:" + baseline.getResultValidationRunId();
        TaskResult created = result(101L, TASK_ID, marker);
        taskResults.answer("findByTaskConfigIdAndSourceDescriptionOrderByIdAsc",
                List.of(created), TASK_ID, marker);
        taskRunResults.answer("countByTaskResultIdIn", 0L, List.of(101L));
        taskResults.answer("findFingerprintRowsByTaskConfigIdAndIdNotIn",
                currentProtectedResults, TASK_ID, List.of(101L));
        taskRuns.answer("findFingerprintRowsByTaskConfigId", currentRuns, TASK_ID);
        taskRunResults.answer("findFingerprintRowsByTaskConfigId", currentLinks, TASK_ID);

        assertRejectedWithoutMutation(task, report(
                "result", baseline.getResultReportToken(), List.of(101L)));
    }

    private void assertForbiddenBatchSideEffect(
            List<String> baselineResults,
            List<String> currentResults,
            List<String> baselineRuns,
            List<String> currentProtectedRuns,
            List<String> baselineLinks,
            List<String> currentProtectedLinks) {
        TaskConfig task = taskAt(OnboardingStep.BATCH_CODE);
        stubTask(task);
        taskResults.answer("findFingerprintRowsByTaskConfigId", baselineResults, TASK_ID);
        taskRuns.answer("findFingerprintRowsByTaskConfigId", baselineRuns, TASK_ID);
        taskRunResults.answer("findFingerprintRowsByTaskConfigId", baselineLinks, TASK_ID);
        service.get(TASK_ID);
        TaskOnboardingContext baseline = readContext(task);
        String marker = "BATCH_VALIDATION:" + baseline.getBatchValidationMarker();
        TaskRun createdRun = run(301L, TASK_ID, marker, "PENDING");
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(createdRun), TASK_ID, marker);
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", List.of(link(401L, 301L, 11L)), 301L);
        taskRunResults.answer("countLinkedResultsForRunAndTask", 1L, 301L, TASK_ID, List.of(11L));
        taskResults.answer("findFingerprintRowsByTaskConfigId", currentResults, TASK_ID);
        taskRuns.answer("findFingerprintRowsByTaskConfigIdAndIdNot",
                currentProtectedRuns, TASK_ID, 301L);
        taskRunResults.answer("findFingerprintRowsByTaskConfigIdAndTaskRunIdNot",
                currentProtectedLinks, TASK_ID, 301L);

        assertRejectedWithoutMutation(task, report(
                "batch", baseline.getBatchReportToken(), List.of(301L, 11L)));
    }

    private TaskOnboardingContext readContext(TaskConfig task) {
        try {
            return context(task);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void assertInvalidProvenance(TaskConfig task, TaskOnboardingReportRequest request) {
        long resultQueryCount = taskResults.callCount(
                "findByTaskConfigIdAndSourceDescriptionOrderByIdAsc");
        assertInvalidResultCallback(task, request);
        assertEquals(resultQueryCount, taskResults.callCount(
                "findByTaskConfigIdAndSourceDescriptionOrderByIdAsc"));
    }

    private void assertInvalidBatch(TaskConfig task, TaskOnboardingReportRequest request) {
        stubTask(task);
        assertRejectedWithoutMutation(task, request);
    }

    private void assertRejectedBatchRun(TaskRun run) {
        TaskConfig task = batchCodeTask("run", "token", List.of(11L));
        stubTask(task);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run), TASK_ID, "BATCH_VALIDATION:run");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", List.of(link(21L, 9L, 11L)), 9L);
        taskResults.answer("findByTaskConfigIdOrderByIdAsc",
                List.of(result(11L, TASK_ID, "formal")), TASK_ID);
        taskRunResults.answer("countLinkedResultsForRunAndTask", 1L, 9L, TASK_ID, List.of(11L));
        assertRejectedWithoutMutation(task, report("batch", "token", List.of(9L, 11L)));
    }

    private void assertRejectedResultMetadata(String resultContent) {
        TaskConfig task = resultCodeTask("run", "token");
        TaskResult result = result(1L, TASK_ID, "RESULT_VALIDATION:run");
        result.setResultContent(resultContent);
        stubTask(task);
        taskResults.answer("findByTaskConfigIdAndSourceDescriptionOrderByIdAsc",
                List.of(result), TASK_ID, "RESULT_VALIDATION:run");
        assertRejectedWithoutMutation(task, report("result", "token", List.of(1L)));
    }

    private void assertRejectedBatchMetadata(String aiPromptJson) {
        TaskConfig task = batchCodeTask("run", "token", List.of(11L));
        TaskRun run = run(9L, TASK_ID, "BATCH_VALIDATION:run", "PENDING");
        run.setAiPromptJson(aiPromptJson);
        stubTask(task);
        taskRuns.answer("findByTaskConfigIdAndReasonOrderByIdAsc",
                List.of(run), TASK_ID, "BATCH_VALIDATION:run");
        taskRunResults.answer("findByTaskRunIdOrderByIdAsc", List.of(link(21L, 9L, 11L)), 9L);
        taskResults.answer("findByTaskConfigIdOrderByIdAsc",
                List.of(result(11L, TASK_ID, "formal")), TASK_ID);
        taskRunResults.answer("countLinkedResultsForRunAndTask", 1L, 9L, TASK_ID, List.of(11L));
        assertRejectedWithoutMutation(task, report("batch", "token", List.of(9L, 11L)));
    }

    private void assertRejectedWithoutMutation(TaskConfig task, TaskOnboardingReportRequest request) {
        String step = task.getOnboardingStep();
        String status = task.getOnboardingStatus();
        String context = task.getOnboardingContext();
        long saveCount = taskConfigs.callCount("save");

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.report(TASK_ID, request));
        assertFalse(error instanceof NullPointerException);

        assertEquals(step, task.getOnboardingStep());
        assertEquals(status, task.getOnboardingStatus());
        assertEquals(context, task.getOnboardingContext());
        assertEquals(saveCount, taskConfigs.callCount("save"));
    }

    private void stubTask(TaskConfig task) {
        taskConfigs.answer("findById", Optional.of(task), TASK_ID);
        taskConfigs.answer("findByIdForUpdate", Optional.of(task), TASK_ID);
    }

    private void stubTaskForUpdate(TaskConfig task) {
        taskConfigs.answer("findByIdForUpdate", Optional.of(task), TASK_ID);
    }

    private void assertControlledContextFailure(TaskConfig task) {
        RuntimeException error = assertThrows(RuntimeException.class, () -> service.get(TASK_ID));
        assertTrue(error instanceof IllegalStateException);
        assertFalse(error instanceof NullPointerException);
        assertTrue(error.getMessage() != null && !error.getMessage().isBlank());
        assertEquals(0, taskConfigs.callCount("save"));
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
        setEmptyBaseline(context, "RESULT");
        task.setOnboardingContext(write(context));
        return task;
    }

    private static TaskConfig batchCodeTask(String runId, String token, List<Long> baselineResultIds) {
        TaskConfig task = taskAt(OnboardingStep.BATCH_CODE);
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setBatchValidationMarker(runId);
        context.setBatchReportToken(token);
        setEmptyBaseline(context, "BATCH");
        task.setOnboardingContext(write(context));
        return task;
    }

    private static TaskConfig resultGenerationTask(String runId, List<Long> validationIds) {
        TaskConfig task = taskAt(OnboardingStep.RESULT_GENERATION);
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setResultValidationRunId(runId);
        context.setResultValidationIds(validationIds);
        task.setOnboardingContext(write(context));
        return task;
    }

    private static TaskConfig batchGenerationTask(String marker, Long runId, List<Long> resultIds) {
        TaskConfig task = taskAt(OnboardingStep.BATCH_GENERATION);
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setBatchValidationMarker(marker);
        context.setBatchValidationTaskRunId(runId);
        context.setBatchValidationResultIds(resultIds);
        task.setOnboardingContext(write(context));
        return task;
    }

    private static TaskConfig configInput(String name, String description, String selectedTables) {
        TaskConfig input = new TaskConfig();
        input.setTaskName(name);
        input.setProjectId(3L);
        input.setCliId("codex");
        input.setDatabaseConfigId(5L);
        input.setTaskDesc(description);
        input.setSelectedTables(selectedTables);
        return input;
    }

    private static void setEmptyBaseline(TaskOnboardingContext context, String stage) {
        context.setBaselineStage(stage);
        context.setBaselineResultCount(0);
        context.setBaselineResultFingerprint(EMPTY_FINGERPRINT);
        context.setBaselineRunCount(0);
        context.setBaselineRunFingerprint(EMPTY_FINGERPRINT);
        context.setBaselineLinkCount(0);
        context.setBaselineLinkFingerprint(EMPTY_FINGERPRINT);
    }

    private static TaskOnboardingReportRequest report(String stage, String token, List<Long> ids) {
        TaskOnboardingReportRequest request = new TaskOnboardingReportRequest();
        request.setStage(stage);
        request.setToken(token);
        request.setArtifact("src/result-generator.py");
        request.setArtifactHash(ARTIFACT_HASH);
        request.setEntityIds(ids);
        return request;
    }

    private static TaskResult result(Long id, Long taskId, String marker) {
        TaskResult result = new TaskResult();
        result.setId(id);
        result.setTaskConfigId(taskId);
        result.setSourceDescription(marker);
        result.setResultContent(validationMetadata(marker));
        result.setResultName("result-" + id);
        result.setProjectId(3L);
        return result;
    }

    private static TaskRun run(Long id, Long taskId, String marker, String status) {
        TaskRun run = new TaskRun();
        run.setId(id);
        run.setTaskConfigId(taskId);
        run.setReason(marker);
        run.setAiPromptJson(validationMetadata(marker));
        run.setStatus(status);
        run.setTaskName("validation run");
        run.setProjectId(3L);
        run.setCliId("codex");
        return run;
    }

    private static String validationMetadata(String marker) {
        return "{\"_meta\":{\"validationRunId\":\"" + marker + "\"}}";
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
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == short.class) {
                return (short) 0;
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
