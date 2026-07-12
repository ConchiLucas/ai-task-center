package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.dto.TaskOnboardingReportRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.model.TaskRunResult;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TaskOnboardingCallbackValidator {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final TaskResultRepository taskResultRepository;
    private final TaskRunRepository taskRunRepository;
    private final TaskRunResultRepository taskRunResultRepository;
    private final ObjectMapper objectMapper;
    private final TaskOnboardingSnapshotService snapshotService;

    public TaskOnboardingCallbackValidator(
            TaskResultRepository taskResultRepository,
            TaskRunRepository taskRunRepository,
            TaskRunResultRepository taskRunResultRepository,
            ObjectMapper objectMapper,
            TaskOnboardingSnapshotService snapshotService) {
        this.taskResultRepository = taskResultRepository;
        this.taskRunRepository = taskRunRepository;
        this.taskRunResultRepository = taskRunResultRepository;
        this.objectMapper = objectMapper;
        this.snapshotService = snapshotService;
    }

    public void validateResult(
            TaskConfig task, TaskOnboardingContext context, TaskOnboardingReportRequest request) {
        Provenance provenance = provenance(request);
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
        results.forEach(result -> requireValidationMetadata(
                result.getResultContent(), marker, "TaskResult.resultContent"));
        if (taskRunResultRepository.countByTaskResultIdIn(submittedIds) != 0) {
            throw new IllegalArgumentException("Validation results must not have task-run links");
        }
        snapshotService.validateResultCallback(task.getId(), submittedIds, context);

        context.setResultValidationIds(submittedIds);
        context.setResultArtifactPath(provenance.artifact());
        context.setResultArtifactHash(provenance.hash());
        context.setResultReportToken("");
    }

    public void validateBatch(
            TaskConfig task, TaskOnboardingContext context, TaskOnboardingReportRequest request) {
        Provenance provenance = provenance(request);
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
        requireValidationMetadata(run.getAiPromptJson(), marker, "TaskRun.aiPromptJson");
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

        long exactRelationshipCount = taskRunResultRepository.countLinkedResultsForRunAndTask(
                run.getId(), task.getId(), submittedResultIds);
        if (exactRelationshipCount != submittedResultIds.size()) {
            throw new IllegalArgumentException("Every validation link must belong to this run and task");
        }
        snapshotService.validateBatchCallback(task.getId(), run.getId(), context);

        context.setBatchValidationTaskRunId(run.getId());
        context.setBatchValidationResultIds(submittedResultIds);
        context.setBatchArtifactPath(provenance.artifact());
        context.setBatchArtifactHash(provenance.hash());
        context.setBatchReportToken("");
    }

    private Provenance provenance(TaskOnboardingReportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Missing onboarding callback payload");
        }
        String artifact = request.getArtifact() == null ? "" : request.getArtifact().trim();
        String hash = request.getArtifactHash() == null ? "" : request.getArtifactHash().trim();
        if (!StringUtils.hasText(artifact)) {
            throw new IllegalArgumentException("Callback artifact path is required");
        }
        if (!SHA_256.matcher(hash).matches()) {
            throw new IllegalArgumentException("Callback artifact hash must be lowercase SHA-256");
        }
        return new Provenance(artifact, hash);
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
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
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

    private void requireValidationMetadata(String json, String expectedMarker, String fieldName) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + " must contain valid validation metadata JSON", ex);
        }
        JsonNode validationRunId = root == null ? null : root.path("_meta").path("validationRunId");
        if (validationRunId == null
                || !validationRunId.isTextual()
                || !expectedMarker.equals(validationRunId.textValue())) {
            throw new IllegalArgumentException(
                    fieldName + " _meta.validationRunId must equal " + expectedMarker);
        }
    }

    private static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private record Provenance(String artifact, String hash) {
    }
}
