package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskOnboardingGenerationPhaseServiceTest {
    private static final Long TASK_ID = 7L;
    private static final String RESULT_ATTEMPT = "a".repeat(64);
    private static final String BATCH_ATTEMPT = "b".repeat(64);
    private static final String NEW_RESULT_ATTEMPT = "c".repeat(64);
    private static final String NEW_BATCH_ATTEMPT = "d".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TaskConfigRepository repository;
    private TaskOnboardingCleanupService cleanupService;
    private TaskOnboardingChildTableLock childTableLock;
    private TaskOnboardingGenerationPhaseService service;

    @BeforeEach
    void setUp() {
        repository = mock(TaskConfigRepository.class);
        cleanupService = mock(TaskOnboardingCleanupService.class);
        childTableLock = mock(TaskOnboardingChildTableLock.class);
        service = new TaskOnboardingGenerationPhaseService(
                repository,
                new TaskOnboardingContextCodec(objectMapper),
                cleanupService,
                mock(TaskOnboardingSnapshotService.class),
                mock(TaskOnboardingResponseAssembler.class),
                childTableLock);
    }

    @Test
    void resultPreparationCommitsCleanupFlagAndRetrySkipsCleanup() throws Exception {
        TaskConfig task = resultGenerationTask();
        when(repository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        TaskOnboardingGenerationPhaseService.GenerationAttempt first = service.prepareResult(TASK_ID);
        TaskOnboardingGenerationPhaseService.GenerationAttempt retry = service.prepareResult(TASK_ID);

        assertEquals(RESULT_ATTEMPT, first.generationId());
        assertEquals(first, retry);
        assertEquals(RESULT_ATTEMPT, context(task).getResultCleanupCompletedFor());
        verify(cleanupService, times(1)).deleteResultValidation(TASK_ID, RESULT_ATTEMPT);
        verify(repository, times(1)).save(task);
    }

    @Test
    void batchPreparationCommitsCleanupFlagAndRetrySkipsCleanup() throws Exception {
        TaskConfig task = batchGenerationTask();
        when(repository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        service.prepareBatch(TASK_ID);
        service.prepareBatch(TASK_ID);

        assertEquals(BATCH_ATTEMPT, context(task).getBatchCleanupCompletedFor());
        verify(cleanupService, times(1)).deleteBatchValidation(TASK_ID, 301L, BATCH_ATTEMPT);
        verify(repository, times(1)).save(task);
    }

    @Test
    void completionRejectsDifferentAttemptOrMissingCleanupFlag() {
        TaskConfig task = resultGenerationTask();
        when(repository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        assertThrows(TaskOnboardingStateException.class,
                () -> service.completeResult(TASK_ID, "c".repeat(64), 3));
        assertEquals(OnboardingStep.RESULT_GENERATION.name(), task.getOnboardingStep());
    }

    @Test
    void phaseMethodsDeclareIndependentTransactions() throws Exception {
        List<Method> methods = List.of(
                TaskOnboardingGenerationPhaseService.class.getMethod("prepareResult", Long.class),
                TaskOnboardingGenerationPhaseService.class.getMethod("prepareBatch", Long.class),
                TaskOnboardingGenerationPhaseService.class.getMethod(
                        "completeResult", Long.class, String.class, long.class),
                TaskOnboardingGenerationPhaseService.class.getMethod(
                        "completeBatch", Long.class, String.class, long.class, long.class),
                TaskOnboardingGenerationPhaseService.class.getMethod(
                        "recordGenerationFailure", Long.class, OnboardingStep.class, String.class),
                TaskOnboardingGenerationPhaseService.class.getMethod(
                        "reactivateGenerationRetry", Long.class, OnboardingStep.class));
        for (Method method : methods) {
            assertNotNull(method.getAnnotation(Transactional.class));
        }
    }

    @Test
    void failureIsSanitizedPersistedAndRetryReactivatesSameAttempt() throws Exception {
        TaskConfig task = resultGenerationTask();
        TaskOnboardingContext prepared = context(task);
        prepared.setResultCleanupCompletedFor(RESULT_ATTEMPT);
        task.setOnboardingContext(objectMapper.writeValueAsString(prepared));
        when(repository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        service.recordGenerationFailure(
                TASK_ID,
                OnboardingStep.RESULT_GENERATION,
                RESULT_ATTEMPT,
                "worker secret\n" + "x".repeat(1200));

        assertEquals(OnboardingStatus.FAILED.name(), task.getOnboardingStatus());
        assertTrue(context(task).getErrorMessage().startsWith("worker secret x"));
        assertTrue(context(task).getErrorMessage().length() <= 500);

        service.reactivateGenerationRetry(TASK_ID, OnboardingStep.RESULT_GENERATION);
        assertEquals(OnboardingStatus.ACTIVE.name(), task.getOnboardingStatus());
        assertEquals(RESULT_ATTEMPT, context(task).getResultValidationRunId());
        assertEquals("", context(task).getErrorMessage());
    }

    @Test
    void delayedResultFailureAndCompletionCannotMutateNewerAttempt() throws Exception {
        TaskConfig task = resultGenerationTask();
        TaskOnboardingContext newer = context(task);
        newer.setResultValidationRunId(NEW_RESULT_ATTEMPT);
        newer.setResultCleanupCompletedFor(NEW_RESULT_ATTEMPT);
        task.setOnboardingContext(objectMapper.writeValueAsString(newer));
        when(repository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        String unchangedContext = task.getOnboardingContext();

        assertThrows(TaskOnboardingStateException.class, () -> service.recordGenerationFailure(
                TASK_ID, OnboardingStep.RESULT_GENERATION, RESULT_ATTEMPT, "delayed failure"));
        assertThrows(TaskOnboardingStateException.class,
                () -> service.completeResult(TASK_ID, RESULT_ATTEMPT, 2));

        assertEquals(OnboardingStep.RESULT_GENERATION.name(), task.getOnboardingStep());
        assertEquals(OnboardingStatus.ACTIVE.name(), task.getOnboardingStatus());
        assertEquals(unchangedContext, task.getOnboardingContext());
    }

    @Test
    void delayedBatchFailureAndCompletionCannotMutateNewerAttempt() throws Exception {
        TaskConfig task = batchGenerationTask();
        TaskOnboardingContext newer = context(task);
        newer.setBatchValidationMarker(NEW_BATCH_ATTEMPT);
        newer.setBatchCleanupCompletedFor(NEW_BATCH_ATTEMPT);
        newer.setBatchExpectedResultCount(1);
        newer.setBatchExpectedResultFingerprint(
                TaskOnboardingGenerationPhaseService.fingerprint(List.of(201L)));
        task.setOnboardingContext(objectMapper.writeValueAsString(newer));
        when(repository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        String unchangedContext = task.getOnboardingContext();

        assertThrows(TaskOnboardingStateException.class, () -> service.recordGenerationFailure(
                TASK_ID, OnboardingStep.BATCH_GENERATION, BATCH_ATTEMPT, "delayed failure"));
        assertThrows(TaskOnboardingStateException.class,
                () -> service.completeBatch(TASK_ID, BATCH_ATTEMPT, 1, 1));

        assertEquals(OnboardingStep.BATCH_GENERATION.name(), task.getOnboardingStep());
        assertEquals(OnboardingStatus.ACTIVE.name(), task.getOnboardingStatus());
        assertEquals(unchangedContext, task.getOnboardingContext());
    }

    @Test
    void failureRequiresCleanupMarkerForTheSuppliedAttempt() throws Exception {
        TaskConfig resultTask = resultGenerationTask();
        TaskOnboardingContext resultContext = context(resultTask);
        resultContext.setResultCleanupCompletedFor(NEW_RESULT_ATTEMPT);
        resultTask.setOnboardingContext(objectMapper.writeValueAsString(resultContext));
        TaskConfig batchTask = batchGenerationTask();
        TaskOnboardingContext batchContext = context(batchTask);
        batchContext.setBatchCleanupCompletedFor(NEW_BATCH_ATTEMPT);
        batchTask.setOnboardingContext(objectMapper.writeValueAsString(batchContext));
        when(repository.findByIdForUpdate(TASK_ID))
                .thenReturn(Optional.of(resultTask), Optional.of(batchTask));

        assertThrows(TaskOnboardingStateException.class, () -> service.recordGenerationFailure(
                TASK_ID, OnboardingStep.RESULT_GENERATION, RESULT_ATTEMPT, "result failure"));
        assertThrows(TaskOnboardingStateException.class, () -> service.recordGenerationFailure(
                TASK_ID, OnboardingStep.BATCH_GENERATION, BATCH_ATTEMPT, "batch failure"));

        assertEquals(OnboardingStatus.ACTIVE.name(), resultTask.getOnboardingStatus());
        assertEquals(OnboardingStatus.ACTIVE.name(), batchTask.getOnboardingStatus());
        assertEquals(resultContext.getResultCleanupCompletedFor(),
                context(resultTask).getResultCleanupCompletedFor());
        assertEquals(batchContext.getBatchCleanupCompletedFor(),
                context(batchTask).getBatchCleanupCompletedFor());
    }

    @Test
    void batchPreparationSnapshotsExactEligibleResultIdentity() throws Exception {
        TaskConfig task = batchGenerationTask();
        when(repository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        TaskOnboardingGenerationPhaseService.GenerationAttempt attempt = service.prepareBatch(TASK_ID);

        assertEquals(List.of(201L), attempt.expectedResultIds());
        assertEquals(1, context(task).getBatchExpectedResultCount());
        assertEquals(TaskOnboardingGenerationPhaseService.fingerprint(List.of(201L)),
                context(task).getBatchExpectedResultFingerprint());
    }

    @Test
    void overlappingResultCompletionAndFailureRecordingReturnCommittedSuccess() throws Exception {
        TaskConfig task = resultGenerationTask();
        TaskOnboardingContext prepared = context(task);
        prepared.setResultCleanupCompletedFor(RESULT_ATTEMPT);
        task.setOnboardingContext(objectMapper.writeValueAsString(prepared));
        when(repository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        service.completeResult(TASK_ID, RESULT_ATTEMPT, 2);
        TaskOnboardingContext completed = context(task);
        completed.setBaselineStage("BATCH");
        completed.setBaselineResultCount(0);
        completed.setBaselineRunCount(0);
        completed.setBaselineLinkCount(0);
        completed.setBaselineResultFingerprint("0".repeat(64));
        completed.setBaselineRunFingerprint("0".repeat(64));
        completed.setBaselineLinkFingerprint("0".repeat(64));
        task.setOnboardingContext(objectMapper.writeValueAsString(completed));
        service.completeResult(TASK_ID, RESULT_ATTEMPT, 2);
        service.recordGenerationFailure(
                TASK_ID, OnboardingStep.RESULT_GENERATION, RESULT_ATTEMPT, "late failure");

        assertEquals(OnboardingStep.BATCH_CODE.name(), task.getOnboardingStep());
        assertEquals(OnboardingStatus.ACTIVE.name(), task.getOnboardingStatus());
        assertEquals(RESULT_ATTEMPT, context(task).getCompletedResultGenerationId());
    }

    @Test
    void overlappingBatchCompletionIsIdempotentAndLocksChildrenBeforeCoverage() throws Exception {
        TaskConfig task = batchGenerationTask();
        TaskOnboardingContext prepared = context(task);
        prepared.setBatchCleanupCompletedFor(BATCH_ATTEMPT);
        prepared.setBatchExpectedResultCount(1);
        prepared.setBatchExpectedResultFingerprint(
                TaskOnboardingGenerationPhaseService.fingerprint(List.of(201L)));
        task.setOnboardingContext(objectMapper.writeValueAsString(prepared));
        when(repository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        service.completeBatch(TASK_ID, BATCH_ATTEMPT, 1, 1);
        service.completeBatch(TASK_ID, BATCH_ATTEMPT, 1, 1);
        service.recordGenerationFailure(
                TASK_ID, OnboardingStep.BATCH_GENERATION, BATCH_ATTEMPT, "late failure");

        verify(childTableLock).lockForCleanup();
        assertEquals(OnboardingStep.READY.name(), task.getOnboardingStep());
        assertEquals(BATCH_ATTEMPT, context(task).getCompletedBatchGenerationId());
    }

    private TaskConfig resultGenerationTask() {
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setResultValidationRunId(RESULT_ATTEMPT);
        context.setResultValidationIds(List.of(101L));
        return task(OnboardingStep.RESULT_GENERATION, context);
    }

    private TaskConfig batchGenerationTask() {
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setBatchValidationMarker(BATCH_ATTEMPT);
        context.setBatchValidationTaskRunId(301L);
        context.setBatchValidationResultIds(List.of(201L));
        return task(OnboardingStep.BATCH_GENERATION, context);
    }

    private TaskConfig task(OnboardingStep step, TaskOnboardingContext context) {
        TaskConfig task = new TaskConfig();
        task.setId(TASK_ID);
        task.setOnboardingStep(step.name());
        task.setOnboardingStatus(OnboardingStatus.ACTIVE.name());
        try {
            task.setOnboardingContext(objectMapper.writeValueAsString(context));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return task;
    }

    private TaskOnboardingContext context(TaskConfig task) throws Exception {
        return objectMapper.readValue(task.getOnboardingContext(), TaskOnboardingContext.class);
    }
}
