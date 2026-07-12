package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.dto.TaskOnboardingResponse;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.TaskConfigRepository;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskOnboardingGenerationPhaseService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TaskConfigRepository taskConfigRepository;
    private final TaskOnboardingContextCodec contextCodec;
    private final TaskOnboardingCleanupService cleanupService;
    private final TaskOnboardingSnapshotService snapshotService;
    private final TaskOnboardingResponseAssembler responseAssembler;

    public TaskOnboardingGenerationPhaseService(
            TaskConfigRepository taskConfigRepository,
            TaskOnboardingContextCodec contextCodec,
            TaskOnboardingCleanupService cleanupService,
            TaskOnboardingSnapshotService snapshotService,
            TaskOnboardingResponseAssembler responseAssembler) {
        this.taskConfigRepository = taskConfigRepository;
        this.contextCodec = contextCodec;
        this.cleanupService = cleanupService;
        this.snapshotService = snapshotService;
        this.responseAssembler = responseAssembler;
    }

    @Transactional
    public GenerationAttempt prepareResult(Long taskConfigId) {
        TaskConfig task = loadActiveTask(taskConfigId, OnboardingStep.RESULT_GENERATION);
        TaskOnboardingContext context = contextCodec.read(task);
        String generationId = requireAttempt(context.getResultValidationRunId(), "result generation ID");
        String completedFor = context.getResultCleanupCompletedFor();
        if (!StringUtils.hasText(completedFor)) {
            cleanupService.deleteResultValidation(taskConfigId, generationId);
            context.setResultCleanupCompletedFor(generationId);
            saveContext(task, context);
        } else if (!generationId.equals(completedFor)) {
            throw new TaskOnboardingStateException("Result cleanup belongs to a different generation attempt");
        }
        return new GenerationAttempt(generationId);
    }

    @Transactional
    public GenerationAttempt prepareBatch(Long taskConfigId) {
        TaskConfig task = loadActiveTask(taskConfigId, OnboardingStep.BATCH_GENERATION);
        TaskOnboardingContext context = contextCodec.read(task);
        String generationId = requireAttempt(context.getBatchValidationMarker(), "batch generation ID");
        String completedFor = context.getBatchCleanupCompletedFor();
        if (!StringUtils.hasText(completedFor)) {
            cleanupService.deleteBatchValidation(
                    taskConfigId, context.getBatchValidationTaskRunId(), generationId);
            context.setBatchCleanupCompletedFor(generationId);
            saveContext(task, context);
        } else if (!generationId.equals(completedFor)) {
            throw new TaskOnboardingStateException("Batch cleanup belongs to a different generation attempt");
        }
        return new GenerationAttempt(generationId);
    }

    @Transactional
    public TaskOnboardingResponse completeResult(
            Long taskConfigId, String generationId, long insertedCount) {
        TaskConfig task = loadActiveTask(taskConfigId, OnboardingStep.RESULT_GENERATION);
        TaskOnboardingContext context = contextCodec.read(task);
        requirePreparedAttempt(
                generationId,
                context.getResultValidationRunId(),
                context.getResultCleanupCompletedFor(),
                "result");
        if (insertedCount <= 0) {
            throw new TaskOnboardingStateException("Formal result generation created no results");
        }

        task.setOnboardingStep(OnboardingStep.BATCH_CODE.name());
        TaskOnboardingContext nextContext = new TaskOnboardingContext();
        snapshotService.capture(taskConfigId, "BATCH", nextContext);
        nextContext.setBatchValidationMarker(newOpaqueValue());
        nextContext.setBatchReportToken(newOpaqueValue());
        saveContext(task, nextContext);
        return responseAssembler.assemble(task, nextContext);
    }

    @Transactional
    public TaskOnboardingResponse completeBatch(
            Long taskConfigId,
            String generationId,
            long createdRunCount,
            long linkedResultCount) {
        TaskConfig task = loadActiveTask(taskConfigId, OnboardingStep.BATCH_GENERATION);
        TaskOnboardingContext context = contextCodec.read(task);
        requirePreparedAttempt(
                generationId,
                context.getBatchValidationMarker(),
                context.getBatchCleanupCompletedFor(),
                "batch");
        if (createdRunCount <= 0 || linkedResultCount <= 0) {
            throw new TaskOnboardingStateException(
                    "Formal batch generation must create runs and result links");
        }

        task.setOnboardingStep(OnboardingStep.READY.name());
        TaskOnboardingContext nextContext = new TaskOnboardingContext();
        saveContext(task, nextContext);
        return responseAssembler.assemble(task, nextContext);
    }

    private TaskConfig loadActiveTask(Long taskConfigId, OnboardingStep expectedStep) {
        if (taskConfigId == null) {
            throw new IllegalArgumentException("Missing task configuration ID");
        }
        TaskConfig task = taskConfigRepository.findByIdForUpdate(taskConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Task configuration does not exist"));
        if (!OnboardingStatus.ACTIVE.name().equals(task.getOnboardingStatus())) {
            throw new TaskOnboardingStateException("Task onboarding status must be ACTIVE for this operation");
        }
        if (!expectedStep.name().equals(task.getOnboardingStep())) {
            throw new TaskOnboardingStateException(
                    "Task onboarding step must be " + expectedStep + " for this operation");
        }
        return task;
    }

    private void saveContext(TaskConfig task, TaskOnboardingContext context) {
        task.setOnboardingContext(contextCodec.write(context));
        taskConfigRepository.save(task);
    }

    private static String requireAttempt(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new TaskOnboardingStateException("Missing " + label);
        }
        return value;
    }

    private static void requirePreparedAttempt(
            String supplied, String contextAttempt, String cleanupCompletedFor, String stage) {
        if (!StringUtils.hasText(supplied)
                || !supplied.equals(contextAttempt)
                || !supplied.equals(cleanupCompletedFor)) {
            throw new TaskOnboardingStateException(
                    "The " + stage + " generation attempt is not prepared for completion");
        }
    }

    private static String newOpaqueValue() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record GenerationAttempt(String generationId) {
    }
}
