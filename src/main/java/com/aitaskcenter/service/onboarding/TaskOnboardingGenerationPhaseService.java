package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.dto.TaskOnboardingResponse;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.TaskConfigRepository;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;

    public TaskOnboardingGenerationPhaseService(
            TaskConfigRepository taskConfigRepository,
            TaskOnboardingContextCodec contextCodec,
            TaskOnboardingCleanupService cleanupService,
            TaskOnboardingSnapshotService snapshotService,
            TaskOnboardingResponseAssembler responseAssembler) {
        this(taskConfigRepository, contextCodec, cleanupService, snapshotService, responseAssembler, null);
    }

    @Autowired
    public TaskOnboardingGenerationPhaseService(
            TaskConfigRepository taskConfigRepository,
            TaskOnboardingContextCodec contextCodec,
            TaskOnboardingCleanupService cleanupService,
            TaskOnboardingSnapshotService snapshotService,
            TaskOnboardingResponseAssembler responseAssembler,
            JdbcTemplate jdbcTemplate) {
        this.taskConfigRepository = taskConfigRepository;
        this.contextCodec = contextCodec;
        this.cleanupService = cleanupService;
        this.snapshotService = snapshotService;
        this.responseAssembler = responseAssembler;
        this.jdbcTemplate = jdbcTemplate;
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
        return new GenerationAttempt(
                generationId, List.of(), context.isOverwriteExistingFormalResults());
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
            List<Long> expectedIds = sortedDistinct(context.getBatchValidationResultIds());
            context.setBatchExpectedResultCount(expectedIds.size());
            context.setBatchExpectedResultFingerprint(fingerprint(expectedIds));
            saveContext(task, context);
        } else if (!generationId.equals(completedFor)) {
            throw new TaskOnboardingStateException("Batch cleanup belongs to a different generation attempt");
        }
        List<Long> expectedIds = sortedDistinct(context.getBatchValidationResultIds());
        requireExpectedSnapshot(context, expectedIds);
        return new GenerationAttempt(generationId, expectedIds, false);
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
        nextContext.setCompletedResultGenerationId(generationId);
        nextContext.setCompletedResultCount(insertedCount);
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
        verifyBatchCoverage(taskConfigId, generationId, context, createdRunCount, linkedResultCount);

        task.setOnboardingStep(OnboardingStep.READY.name());
        TaskOnboardingContext nextContext = new TaskOnboardingContext();
        nextContext.setCompletedResultGenerationId(context.getCompletedResultGenerationId());
        nextContext.setCompletedResultCount(context.getCompletedResultCount());
        nextContext.setCompletedBatchGenerationId(generationId);
        nextContext.setCompletedBatchRunCount(createdRunCount);
        nextContext.setCompletedBatchLinkCount(linkedResultCount);
        saveContext(task, nextContext);
        return responseAssembler.assemble(task, nextContext);
    }

    private void verifyBatchCoverage(
            Long taskConfigId,
            String generationId,
            TaskOnboardingContext context,
            long createdRunCount,
            long linkedResultCount) {
        List<Long> expected = sortedDistinct(context.getBatchValidationResultIds());
        requireExpectedSnapshot(context, expected);
        if (jdbcTemplate == null) {
            return;
        }
        String reason = "FORMAL_GENERATION:" + generationId;
        List<Long> actual = jdbcTemplate.queryForList("""
                select result.id
                from tb_task_run_result link
                join tb_task_run run on run.id = link.task_run_id
                join tb_task_result result on result.id = link.task_result_id
                where run.task_config_id = ? and run.reason = ? and result.task_config_id = ?
                order by result.id
                """, Long.class, taskConfigId, reason, taskConfigId);
        Long allLinks = jdbcTemplate.queryForObject("""
                select count(*) from tb_task_run_result link
                join tb_task_run run on run.id = link.task_run_id
                where run.task_config_id = ? and run.reason = ?
                """, Long.class, taskConfigId, reason);
        Long runs = jdbcTemplate.queryForObject(
                "select count(*) from tb_task_run where task_config_id = ? and reason = ?",
                Long.class, taskConfigId, reason);
        if (!expected.equals(actual)
                || allLinks == null || allLinks != expected.size()
                || linkedResultCount != expected.size()
                || runs == null || runs != createdRunCount) {
            throw new TaskOnboardingStateException("Formal batches do not exactly cover Phase A results");
        }
    }

    @Transactional
    public void recordGenerationFailure(Long taskConfigId, OnboardingStep expectedStep, String message) {
        TaskConfig task = taskConfigRepository.findByIdForUpdate(taskConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Task configuration does not exist"));
        if (step(task) != expectedStep || !isGenerationStep(expectedStep)) {
            throw new TaskOnboardingStateException("Generation failure no longer matches the current attempt");
        }
        TaskOnboardingContext context = contextCodec.read(task);
        context.setErrorMessage(sanitize(message));
        task.setOnboardingStatus(OnboardingStatus.FAILED.name());
        saveContext(task, context);
    }

    @Transactional
    public void reactivateGenerationRetry(Long taskConfigId, OnboardingStep expectedStep) {
        TaskConfig task = taskConfigRepository.findByIdForUpdate(taskConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Task configuration does not exist"));
        if (step(task) != expectedStep || !isGenerationStep(expectedStep)) {
            throw new TaskOnboardingStateException("Generation retry does not match the current step");
        }
        if (OnboardingStatus.FAILED.name().equals(task.getOnboardingStatus())) {
            TaskOnboardingContext context = contextCodec.read(task);
            context.setErrorMessage("");
            task.setOnboardingStatus(OnboardingStatus.ACTIVE.name());
            saveContext(task, context);
        } else if (!OnboardingStatus.ACTIVE.name().equals(task.getOnboardingStatus())) {
            throw new TaskOnboardingStateException("Task onboarding cannot retry generation");
        }
    }

    @Transactional
    public TaskOnboardingResponse completedResultResponse(Long taskConfigId) {
        TaskConfig task = taskConfigRepository.findByIdForUpdate(taskConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Task configuration does not exist"));
        TaskOnboardingContext context = contextCodec.read(task);
        if (step(task).ordinal() >= OnboardingStep.BATCH_CODE.ordinal()
                && StringUtils.hasText(context.getCompletedResultGenerationId())
                && context.getCompletedResultCount() > 0) {
            return responseAssembler.assemble(task, context);
        }
        return null;
    }

    @Transactional
    public TaskOnboardingResponse completedBatchResponse(Long taskConfigId) {
        TaskConfig task = taskConfigRepository.findByIdForUpdate(taskConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Task configuration does not exist"));
        TaskOnboardingContext context = contextCodec.read(task);
        if (step(task) == OnboardingStep.READY
                && StringUtils.hasText(context.getCompletedBatchGenerationId())
                && context.getCompletedBatchRunCount() > 0
                && context.getCompletedBatchLinkCount() > 0) {
            return responseAssembler.assemble(task, context);
        }
        return null;
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

    static String fingerprint(List<Long> ids) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Long id : ids) {
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(id).array());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<Long> sortedDistinct(List<Long> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new TaskOnboardingStateException("Batch generation has no expected results");
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        if (sorted.stream().distinct().count() != sorted.size()) {
            throw new TaskOnboardingStateException("Batch generation expected result IDs are not unique");
        }
        return List.copyOf(sorted);
    }

    private static void requireExpectedSnapshot(TaskOnboardingContext context, List<Long> ids) {
        if (context.getBatchExpectedResultCount() != ids.size()
                || !fingerprint(ids).equals(context.getBatchExpectedResultFingerprint())) {
            throw new TaskOnboardingStateException("Batch generation expected result snapshot changed");
        }
    }

    private static OnboardingStep step(TaskConfig task) {
        try {
            return OnboardingStep.valueOf(task.getOnboardingStep());
        } catch (RuntimeException ex) {
            throw new TaskOnboardingStateException("Invalid onboarding step", ex);
        }
    }

    private static boolean isGenerationStep(OnboardingStep step) {
        return step == OnboardingStep.RESULT_GENERATION || step == OnboardingStep.BATCH_GENERATION;
    }

    private static String sanitize(String message) {
        String sanitized = message == null ? "Generation failed" : message.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (!StringUtils.hasText(sanitized)) {
            sanitized = "Generation failed";
        }
        return sanitized.substring(0, Math.min(500, sanitized.length()));
    }

    public record GenerationAttempt(
            String generationId, List<Long> expectedResultIds, boolean overwriteExistingFormalResults) {
    }
}
