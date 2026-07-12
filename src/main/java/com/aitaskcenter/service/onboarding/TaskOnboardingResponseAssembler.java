package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.dto.TaskOnboardingNodeResponse;
import com.aitaskcenter.dto.TaskOnboardingResponse;
import com.aitaskcenter.dto.TaskOnboardingResultSummary;
import com.aitaskcenter.dto.TaskOnboardingRunSummary;
import com.aitaskcenter.dto.TaskOnboardingTaskSummary;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TaskOnboardingResponseAssembler {
    private static final List<OnboardingStep> STEPS = List.of(OnboardingStep.values());
    private static final Map<OnboardingStep, String> LABELS = labels();

    private final TaskResultRepository taskResultRepository;
    private final TaskRunRepository taskRunRepository;
    private final TaskOnboardingPromptBuilder promptBuilder;

    public TaskOnboardingResponseAssembler(
            TaskResultRepository taskResultRepository,
            TaskRunRepository taskRunRepository,
            TaskOnboardingPromptBuilder promptBuilder) {
        this.taskResultRepository = taskResultRepository;
        this.taskRunRepository = taskRunRepository;
        this.promptBuilder = promptBuilder;
    }

    public TaskOnboardingResponse assemble(TaskConfig task, TaskOnboardingContext context) {
        OnboardingStep currentStep = step(task);
        OnboardingStatus currentStatus = status(task);
        boolean active = currentStatus == OnboardingStatus.ACTIVE;
        TaskOnboardingResponse response = new TaskOnboardingResponse();
        response.setTask(TaskOnboardingTaskSummary.from(task));
        response.setCurrentStep(currentStep.name());
        response.setCurrentStatus(currentStatus.name());
        response.setNodes(nodes(currentStep, currentStatus));
        response.setAllowedActions(active ? allowedActions(currentStep) : List.of());
        response.setErrorMessage(context.getErrorMessage());

        if (active && currentStep == OnboardingStep.RESULT_CODE) {
            response.setPrompt(promptBuilder.buildResultPrompt(
                    task, context.getResultValidationRunId(), context.getResultReportToken()));
        } else if (active && currentStep == OnboardingStep.BATCH_CODE) {
            response.setPrompt(promptBuilder.buildBatchPrompt(
                    task, context.getBatchValidationMarker(), context.getBatchReportToken()));
        }

        List<TaskOnboardingResultSummary> validationResults = resultValidationRows(task, context);
        response.setValidationResults(validationResults);
        List<TaskOnboardingResultSummary> validationRunResults = List.of();
        if (context.getBatchValidationTaskRunId() != null) {
            String reason = "BATCH_VALIDATION:" + context.getBatchValidationMarker();
            response.setValidationRun(taskRunRepository.findByIdAndTaskConfigIdAndReason(
                            context.getBatchValidationTaskRunId(), task.getId(), reason)
                    .map(TaskOnboardingRunSummary::from)
                    .orElse(null));
            validationRunResults = taskResultRepository.findValidationRunResults(
                            context.getBatchValidationResultIds(),
                            context.getBatchValidationTaskRunId(),
                            task.getId(),
                            reason)
                    .stream()
                    .map(TaskOnboardingResultSummary::from)
                    .toList();
        }
        response.setValidationRunResults(validationRunResults);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("validationResults", (long) validationResults.size());
        counts.put("validationRuns", response.getValidationRun() == null ? 0L : 1L);
        counts.put("validationRunResults", (long) validationRunResults.size());
        response.setCounts(counts);
        return response;
    }

    private List<TaskOnboardingResultSummary> resultValidationRows(
            TaskConfig task, TaskOnboardingContext context) {
        if (context.getResultValidationIds().isEmpty()) {
            return List.of();
        }
        String marker = "RESULT_VALIDATION:" + context.getResultValidationRunId();
        return taskResultRepository.findByIdInAndTaskConfigIdAndSourceDescriptionOrderByIdAsc(
                        context.getResultValidationIds(), task.getId(), marker)
                .stream()
                .map(TaskOnboardingResultSummary::from)
                .toList();
    }

    private List<TaskOnboardingNodeResponse> nodes(
            OnboardingStep currentStep, OnboardingStatus currentStatus) {
        int currentIndex = STEPS.indexOf(currentStep);
        List<TaskOnboardingNodeResponse> nodes = new ArrayList<>();
        for (int index = 0; index < STEPS.size(); index++) {
            OnboardingStep nodeStep = STEPS.get(index);
            String nodeState = index < currentIndex
                    ? OnboardingStatus.COMPLETED.name()
                    : index > currentIndex ? "LOCKED" : currentStatus.name();
            nodes.add(new TaskOnboardingNodeResponse(nodeStep.name(), LABELS.get(nodeStep), nodeState));
        }
        return nodes;
    }

    private static List<String> allowedActions(OnboardingStep step) {
        return switch (step) {
            case RESULT_CODE, BATCH_CODE -> List.of("COPY_PROMPT");
            case RESULT_VALIDATION -> List.of("CONFIRM_RESULT_VALIDATION");
            case RESULT_GENERATION -> List.of("GENERATE_RESULTS");
            case BATCH_VALIDATION -> List.of("CONFIRM_BATCH_VALIDATION");
            case BATCH_GENERATION -> List.of("GENERATE_BATCHES");
            case READY -> List.of();
        };
    }

    private static OnboardingStep step(TaskConfig task) {
        try {
            return OnboardingStep.valueOf(task.getOnboardingStep());
        } catch (RuntimeException ex) {
            throw new TaskOnboardingStateException("Invalid onboarding step: " + task.getOnboardingStep(), ex);
        }
    }

    private static OnboardingStatus status(TaskConfig task) {
        try {
            return OnboardingStatus.valueOf(task.getOnboardingStatus());
        } catch (RuntimeException ex) {
            throw new TaskOnboardingStateException("Invalid onboarding status: " + task.getOnboardingStatus(), ex);
        }
    }

    private static Map<OnboardingStep, String> labels() {
        Map<OnboardingStep, String> labels = new EnumMap<>(OnboardingStep.class);
        labels.put(OnboardingStep.RESULT_CODE, "Customize result code");
        labels.put(OnboardingStep.RESULT_VALIDATION, "Validate task results");
        labels.put(OnboardingStep.RESULT_GENERATION, "Generate task results");
        labels.put(OnboardingStep.BATCH_CODE, "Customize batch code");
        labels.put(OnboardingStep.BATCH_VALIDATION, "Validate task batch");
        labels.put(OnboardingStep.BATCH_GENERATION, "Generate task batches");
        labels.put(OnboardingStep.READY, "Ready");
        return Map.copyOf(labels);
    }
}
