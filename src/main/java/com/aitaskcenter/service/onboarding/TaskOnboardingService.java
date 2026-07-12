package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.dto.TaskOnboardingNodeResponse;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskOnboardingService {
    private static final List<OnboardingStep> STEPS = List.of(OnboardingStep.values());
    private static final Map<OnboardingStep, OnboardingStep> TRANSITIONS = transitions();
    private static final Map<OnboardingStep, String> LABELS = labels();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TaskConfigRepository taskConfigRepository;
    private final TaskResultRepository taskResultRepository;
    private final TaskRunRepository taskRunRepository;
    private final TaskRunResultRepository taskRunResultRepository;
    private final TaskOnboardingPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    public TaskOnboardingService(
            TaskConfigRepository taskConfigRepository,
            TaskResultRepository taskResultRepository,
            TaskRunRepository taskRunRepository,
            TaskRunResultRepository taskRunResultRepository,
            TaskOnboardingPromptBuilder promptBuilder,
            ObjectMapper objectMapper) {
        this.taskConfigRepository = taskConfigRepository;
        this.taskResultRepository = taskResultRepository;
        this.taskRunRepository = taskRunRepository;
        this.taskRunResultRepository = taskRunResultRepository;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TaskOnboardingResponse get(Long taskConfigId) {
        TaskConfig task = loadTask(taskConfigId);
        TaskOnboardingContext context = readContext(task);
        OnboardingStep step = readStep(task);
        boolean changed = false;

        if (step == OnboardingStep.RESULT_CODE) {
            if (!StringUtils.hasText(context.getResultValidationRunId())) {
                context.setResultValidationRunId(newOpaqueValue());
                changed = true;
            }
            if (!StringUtils.hasText(context.getResultReportToken())) {
                context.setResultReportToken(newOpaqueValue());
                changed = true;
            }
        } else if (step == OnboardingStep.BATCH_CODE) {
            boolean initializeBatch = !StringUtils.hasText(context.getBatchValidationMarker())
                    || !StringUtils.hasText(context.getBatchReportToken());
            if (!StringUtils.hasText(context.getBatchValidationMarker())) {
                context.setBatchValidationMarker(newOpaqueValue());
                changed = true;
            }
            if (!StringUtils.hasText(context.getBatchReportToken())) {
                context.setBatchReportToken(newOpaqueValue());
                changed = true;
            }
            if (initializeBatch) {
                context.setBatchValidationResultIds(taskResultRepository
                        .findByTaskConfigIdOrderByIdAsc(taskConfigId)
                        .stream()
                        .map(TaskResult::getId)
                        .toList());
                changed = true;
            }
        }

        if (changed) {
            saveContext(task, context);
        }
        return buildResponse(task, context);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TaskOnboardingResponse report(Long taskConfigId, TaskOnboardingReportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Missing onboarding callback payload");
        }
        TaskConfig task = loadTask(taskConfigId);
        TaskOnboardingContext context = readContext(task);
        OnboardingStep step = readStep(task);

        if (step == OnboardingStep.RESULT_CODE) {
            validateResultReport(task, context, request);
            advance(task, OnboardingStep.RESULT_CODE, OnboardingStep.RESULT_VALIDATION);
        } else if (step == OnboardingStep.BATCH_CODE) {
            validateBatchReport(task, context, request);
            advance(task, OnboardingStep.BATCH_CODE, OnboardingStep.BATCH_VALIDATION);
        } else {
            throw new IllegalArgumentException("The current onboarding step does not accept callbacks");
        }

        task.setOnboardingStatus(OnboardingStatus.ACTIVE.name());
        context.setErrorMessage("");
        saveContext(task, context);
        return buildResponse(task, context);
    }

    @Transactional
    public TaskOnboardingResponse confirmResultValidation(Long taskConfigId) {
        return confirm(taskConfigId, OnboardingStep.RESULT_VALIDATION, OnboardingStep.RESULT_GENERATION);
    }

    @Transactional
    public TaskOnboardingResponse confirmBatchValidation(Long taskConfigId) {
        return confirm(taskConfigId, OnboardingStep.BATCH_VALIDATION, OnboardingStep.BATCH_GENERATION);
    }

    private TaskOnboardingResponse confirm(
            Long taskConfigId, OnboardingStep expected, OnboardingStep target) {
        TaskConfig task = loadTask(taskConfigId);
        TaskOnboardingContext context = readContext(task);
        advance(task, expected, target);
        task.setOnboardingStatus(OnboardingStatus.ACTIVE.name());
        context.setErrorMessage("");
        saveContext(task, context);
        return buildResponse(task, context);
    }

    private void validateResultReport(
            TaskConfig task, TaskOnboardingContext context, TaskOnboardingReportRequest request) {
        requireStage(request, "result");
        requireToken(context.getResultReportToken(), request.getToken());
        List<Long> submittedIds = requireDistinctIds(request.getEntityIds(), 1, 3, "result IDs");
        String marker = "RESULT_VALIDATION:" + requireText(
                context.getResultValidationRunId(), "Missing result validation run ID");
        List<TaskResult> results = taskResultRepository
                .findByTaskConfigIdAndSourceDescriptionOrderByIdAsc(task.getId(), marker);
        List<Long> databaseIds = results.stream().map(TaskResult::getId).toList();

        if (!submittedIds.equals(databaseIds)
                || results.stream().anyMatch(result -> !task.getId().equals(result.getTaskConfigId())
                        || !marker.equals(result.getSourceDescription()))) {
            throw new IllegalArgumentException("Submitted results do not exactly match the marked validation rows");
        }
        if (taskRunResultRepository.countByTaskResultIdIn(submittedIds) != 0) {
            throw new IllegalArgumentException("Validation results must not have task-run links");
        }

        context.setResultValidationIds(submittedIds);
        context.setResultArtifactPath(textOrEmpty(request.getArtifact()));
        context.setResultArtifactHash(textOrEmpty(request.getArtifactHash()));
        context.setResultReportToken("");
    }

    private void validateBatchReport(
            TaskConfig task, TaskOnboardingContext context, TaskOnboardingReportRequest request) {
        requireStage(request, "batch");
        requireToken(context.getBatchReportToken(), request.getToken());
        List<Long> entityIds = requireDistinctIds(request.getEntityIds(), 2, Integer.MAX_VALUE, "batch entity IDs");
        Long submittedRunId = entityIds.get(0);
        List<Long> submittedResultIds = List.copyOf(entityIds.subList(1, entityIds.size()));
        String validationRunId = requireText(
                context.getBatchValidationMarker(), "Missing batch validation run ID");
        String marker = "BATCH_VALIDATION:" + validationRunId;

        List<TaskRun> runs = taskRunRepository.findByTaskConfigIdAndReasonOrderByIdAsc(task.getId(), marker);
        if (runs.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one marked validation run");
        }
        TaskRun run = runs.get(0);
        if (!submittedRunId.equals(run.getId())
                || !task.getId().equals(run.getTaskConfigId())
                || !marker.equals(run.getReason())) {
            throw new IllegalArgumentException("Submitted validation run does not match the marked task run");
        }
        if (!"PENDING".equals(run.getStatus()) || run.getStartTime() != null || run.getEndTime() != null) {
            throw new IllegalArgumentException("Validation run must be pending and unstarted");
        }

        List<TaskRunResult> links = taskRunResultRepository.findByTaskRunIdOrderByIdAsc(run.getId());
        List<Long> linkedResultIds = links.stream().map(TaskRunResult::getTaskResultId).toList();
        if (links.isEmpty()
                || !submittedResultIds.equals(linkedResultIds)
                || links.stream().anyMatch(link -> !run.getId().equals(link.getTaskRunId()))) {
            throw new IllegalArgumentException("Submitted result IDs do not exactly match the validation run links");
        }

        List<Long> currentTaskResultIds = taskResultRepository.findByTaskConfigIdOrderByIdAsc(task.getId())
                .stream()
                .map(TaskResult::getId)
                .toList();
        if (!currentTaskResultIds.equals(context.getBatchValidationResultIds())) {
            throw new IllegalArgumentException("Task results changed while batch validation code was running");
        }
        long exactRelationshipCount = taskRunResultRepository.countLinkedResultsForRunAndTask(
                run.getId(), task.getId(), submittedResultIds);
        if (exactRelationshipCount != submittedResultIds.size()
                || !currentTaskResultIds.containsAll(submittedResultIds)) {
            throw new IllegalArgumentException("Every validation link must belong to this run and task");
        }

        context.setBatchValidationTaskRunId(run.getId());
        context.setBatchValidationResultIds(submittedResultIds);
        context.setBatchArtifactPath(textOrEmpty(request.getArtifact()));
        context.setBatchArtifactHash(textOrEmpty(request.getArtifactHash()));
        context.setBatchReportToken("");
    }

    private TaskOnboardingResponse buildResponse(TaskConfig task, TaskOnboardingContext context) {
        OnboardingStep currentStep = readStep(task);
        TaskOnboardingResponse response = new TaskOnboardingResponse();
        response.setTask(task);
        response.setCurrentStep(currentStep.name());
        response.setCurrentStatus(readStatus(task).name());
        response.setNodes(buildNodes(currentStep, readStatus(task)));
        response.setAllowedActions(allowedActions(currentStep));
        response.setErrorMessage(textOrEmpty(context.getErrorMessage()));

        if (currentStep == OnboardingStep.RESULT_CODE) {
            response.setPrompt(promptBuilder.buildResultPrompt(
                    task, context.getResultValidationRunId(), context.getResultReportToken()));
        } else if (currentStep == OnboardingStep.BATCH_CODE) {
            response.setPrompt(promptBuilder.buildBatchPrompt(
                    task, context.getBatchValidationMarker(), context.getBatchReportToken()));
        }

        List<TaskResult> validationResults = orderedResults(context.getResultValidationIds());
        response.setValidationResults(validationResults);
        List<TaskResult> validationRunResults = List.of();
        if (context.getBatchValidationTaskRunId() != null) {
            response.setValidationRun(taskRunRepository.findById(context.getBatchValidationTaskRunId()).orElse(null));
            validationRunResults = orderedResults(context.getBatchValidationResultIds());
        }
        response.setValidationRunResults(validationRunResults);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("validationResults", (long) validationResults.size());
        counts.put("validationRuns", response.getValidationRun() == null ? 0L : 1L);
        counts.put("validationRunResults", (long) validationRunResults.size());
        response.setCounts(counts);
        return response;
    }

    private List<TaskResult> orderedResults(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, TaskResult> byId = taskResultRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(TaskResult::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(result -> result != null).toList();
    }

    private List<TaskOnboardingNodeResponse> buildNodes(
            OnboardingStep currentStep, OnboardingStatus currentStatus) {
        int currentIndex = STEPS.indexOf(currentStep);
        List<TaskOnboardingNodeResponse> nodes = new ArrayList<>();
        for (int index = 0; index < STEPS.size(); index++) {
            OnboardingStep step = STEPS.get(index);
            String state;
            if (index < currentIndex) {
                state = OnboardingStatus.COMPLETED.name();
            } else if (index > currentIndex) {
                state = "LOCKED";
            } else if (currentStatus == OnboardingStatus.FAILED || currentStatus == OnboardingStatus.STALE) {
                state = currentStatus.name();
            } else {
                state = OnboardingStatus.ACTIVE.name();
            }
            nodes.add(new TaskOnboardingNodeResponse(step.name(), LABELS.get(step), state));
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

    private void advance(TaskConfig task, OnboardingStep expected, OnboardingStep target) {
        OnboardingStep current = readStep(task);
        if (current != expected || TRANSITIONS.get(current) != target) {
            throw new IllegalArgumentException("Invalid onboarding transition from " + current + " to " + target);
        }
        task.setOnboardingStep(target.name());
    }

    private TaskConfig loadTask(Long taskConfigId) {
        if (taskConfigId == null) {
            throw new IllegalArgumentException("Missing task configuration ID");
        }
        return taskConfigRepository.findById(taskConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Task configuration does not exist"));
    }

    private TaskOnboardingContext readContext(TaskConfig task) {
        try {
            String json = StringUtils.hasText(task.getOnboardingContext()) ? task.getOnboardingContext() : "{}";
            return objectMapper.readValue(json, TaskOnboardingContext.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid task onboarding context", ex);
        }
    }

    private void saveContext(TaskConfig task, TaskOnboardingContext context) {
        try {
            String json = objectMapper.writeValueAsString(context);
            task.setOnboardingContext(json);
            taskConfigRepository.save(task);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize task onboarding context", ex);
        }
    }

    private static OnboardingStep readStep(TaskConfig task) {
        try {
            return OnboardingStep.valueOf(task.getOnboardingStep());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid onboarding step: " + task.getOnboardingStep(), ex);
        }
    }

    private static OnboardingStatus readStatus(TaskConfig task) {
        try {
            return OnboardingStatus.valueOf(task.getOnboardingStatus());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid onboarding status: " + task.getOnboardingStatus(), ex);
        }
    }

    private static void requireStage(TaskOnboardingReportRequest request, String expected) {
        if (!expected.equals(request.getStage())) {
            throw new IllegalArgumentException("Callback stage must be " + expected);
        }
    }

    private static void requireToken(String expected, String actual) {
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(actual)) {
            throw new IllegalArgumentException("Callback token is missing or already used");
        }
        boolean equal = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
        if (!equal) {
            throw new IllegalArgumentException("Callback token is invalid");
        }
    }

    private static List<Long> requireDistinctIds(
            Collection<Long> values, int minimum, int maximum, String label) {
        List<Long> ids = values == null ? List.of() : List.copyOf(values);
        if (ids.size() < minimum || ids.size() > maximum || ids.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("Invalid " + label + " count");
        }
        Set<Long> unique = new LinkedHashSet<>(ids);
        if (unique.size() != ids.size()) {
            throw new IllegalArgumentException("Duplicate " + label + " are not allowed");
        }
        return ids;
    }

    private static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String newOpaqueValue() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Map<OnboardingStep, OnboardingStep> transitions() {
        Map<OnboardingStep, OnboardingStep> transitions = new EnumMap<>(OnboardingStep.class);
        for (int index = 0; index < STEPS.size() - 1; index++) {
            transitions.put(STEPS.get(index), STEPS.get(index + 1));
        }
        return Map.copyOf(transitions);
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
