package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TaskOnboardingContextCodec {
    private final ObjectMapper objectMapper;

    public TaskOnboardingContextCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TaskOnboardingContext read(TaskConfig task) {
        TaskOnboardingContext context;
        try {
            String json = StringUtils.hasText(task.getOnboardingContext()) ? task.getOnboardingContext() : "{}";
            context = objectMapper.readValue(json, TaskOnboardingContext.class);
        } catch (JsonProcessingException ex) {
            throw new TaskOnboardingStateException("Invalid task onboarding context JSON", ex);
        }
        validate(task, context);
        return context;
    }

    public String write(TaskOnboardingContext context) {
        validate(null, context);
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException ex) {
            throw new TaskOnboardingStateException("Unable to serialize task onboarding context", ex);
        }
    }

    private void validate(TaskConfig task, TaskOnboardingContext context) {
        if (context == null) {
            throw new TaskOnboardingStateException("Task onboarding context must be a JSON object");
        }
        if (context.getResultValidationIds() == null || context.getBatchValidationResultIds() == null) {
            throw new TaskOnboardingStateException("Task onboarding context ID lists must not be null");
        }
        requireNonNullStrings(context);
        boolean resultRunPresent = StringUtils.hasText(context.getResultValidationRunId());
        boolean resultTokenPresent = StringUtils.hasText(context.getResultReportToken());
        if (!resultRunPresent && resultTokenPresent) {
            throw new TaskOnboardingStateException("Result callback identity is incomplete");
        }
        boolean batchRunPresent = StringUtils.hasText(context.getBatchValidationMarker());
        boolean batchTokenPresent = StringUtils.hasText(context.getBatchReportToken());
        if (!batchRunPresent && batchTokenPresent) {
            throw new TaskOnboardingStateException("Batch callback identity is incomplete");
        }
        validateBaselineShape(context);
        if (task != null && OnboardingStep.RESULT_CODE.name().equals(task.getOnboardingStep()) && resultRunPresent) {
            requireBaseline(context, "RESULT");
        }
        if (task != null && OnboardingStep.BATCH_CODE.name().equals(task.getOnboardingStep()) && batchRunPresent) {
            requireBaseline(context, "BATCH");
        }
    }

    private void requireNonNullStrings(TaskOnboardingContext context) {
        if (context.getResultValidationRunId() == null
                || context.getResultReportToken() == null
                || context.getResultArtifactPath() == null
                || context.getResultArtifactHash() == null
                || context.getResultCleanupCompletedFor() == null
                || context.getCompletedResultGenerationId() == null
                || context.getBatchValidationMarker() == null
                || context.getBatchReportToken() == null
                || context.getBatchArtifactPath() == null
                || context.getBatchArtifactHash() == null
                || context.getBatchCleanupCompletedFor() == null
                || context.getBatchExpectedResultFingerprint() == null
                || context.getCompletedBatchGenerationId() == null
                || context.getErrorMessage() == null
                || context.getBaselineStage() == null
                || context.getBaselineResultFingerprint() == null
                || context.getBaselineRunFingerprint() == null
                || context.getBaselineLinkFingerprint() == null) {
            throw new TaskOnboardingStateException("Task onboarding context strings must not be null");
        }
    }

    private void requireBaseline(TaskOnboardingContext context, String stage) {
        if (!stage.equals(context.getBaselineStage())
                || context.getBaselineResultCount() < 0
                || context.getBaselineRunCount() < 0
                || context.getBaselineLinkCount() < 0
                || !isSha256(context.getBaselineResultFingerprint())
                || !isSha256(context.getBaselineRunFingerprint())
                || !isSha256(context.getBaselineLinkFingerprint())) {
            throw new TaskOnboardingStateException("Task onboarding baseline is incomplete or inconsistent");
        }
    }

    private void validateBaselineShape(TaskOnboardingContext context) {
        boolean uninitialized = !StringUtils.hasText(context.getBaselineStage())
                && context.getBaselineResultCount() == -1
                && context.getBaselineRunCount() == -1
                && context.getBaselineLinkCount() == -1
                && !StringUtils.hasText(context.getBaselineResultFingerprint())
                && !StringUtils.hasText(context.getBaselineRunFingerprint())
                && !StringUtils.hasText(context.getBaselineLinkFingerprint());
        boolean complete = List.of("RESULT", "BATCH").contains(context.getBaselineStage())
                && context.getBaselineResultCount() >= 0
                && context.getBaselineRunCount() >= 0
                && context.getBaselineLinkCount() >= 0
                && isSha256(context.getBaselineResultFingerprint())
                && isSha256(context.getBaselineRunFingerprint())
                && isSha256(context.getBaselineLinkFingerprint());
        if (!uninitialized && !complete) {
            throw new TaskOnboardingStateException("Task onboarding baseline shape is inconsistent");
        }
    }

    private boolean isSha256(String value) {
        return value.matches("[0-9a-f]{64}");
    }
}
