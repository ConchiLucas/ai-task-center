package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.model.TaskRunResult;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskOnboardingCleanupService {
    private static final String LOCK_RESULT_ROWS = """
            select id
            from tb_task_result
            where task_config_id = :taskConfigId
              and source_description = :marker
              and id in (:resultIds)
            order by id
            for update
            """;
    private static final String LOCK_BATCH_RESULT_ROWS = """
            select id
            from tb_task_result
            where task_config_id = :taskConfigId
              and id in (:resultIds)
            order by id
            for update
            """;
    private static final String LOCK_RUN_ROW = """
            select id
            from tb_task_run
            where id = :taskRunId
              and task_config_id = :taskConfigId
              and reason = :marker
            for update
            """;
    private static final String LOCK_LINK_ROWS = """
            select link.id
            from tb_task_run_result link
            join tb_task_run run on run.id = link.task_run_id
            where link.task_run_id = :taskRunId
              and run.task_config_id = :taskConfigId
              and run.reason = :marker
            order by link.id
            for update of link
            """;
    private static final String LOCK_EXECUTION_LOG_ROWS = """
            select log.id
            from tb_task_execution_log log
            join tb_task_run run on run.id = log.task_run_id
            where log.task_run_id = :taskRunId
              and run.task_config_id = :taskConfigId
              and run.reason = :marker
            order by log.id
            for update of log
            """;
    private static final String DELETE_RESULT_ROWS = """
            delete from tb_task_result
            where task_config_id = :taskConfigId
              and source_description = :marker
              and id in (:resultIds)
            """;
    private static final String DELETE_EXECUTION_LOGS = """
            delete from tb_task_execution_log log
            using tb_task_run run
            where log.task_run_id = :taskRunId
              and run.id = log.task_run_id
              and run.task_config_id = :taskConfigId
              and run.reason = :marker
            """;
    private static final String DELETE_RUN_LINKS = """
            delete from tb_task_run_result link
            using tb_task_run run
            where link.task_run_id = :taskRunId
              and run.id = link.task_run_id
              and run.task_config_id = :taskConfigId
              and run.reason = :marker
            """;
    private static final String DELETE_RUN = """
            delete from tb_task_run
            where id = :taskRunId
              and task_config_id = :taskConfigId
              and reason = :marker
            """;
    private static final String COUNT_EXECUTION_LOGS = """
            select count(*)
            from tb_task_execution_log log
            where log.task_run_id = :taskRunId
            """;

    private final TaskConfigRepository taskConfigRepository;
    private final TaskResultRepository taskResultRepository;
    private final TaskRunRepository taskRunRepository;
    private final TaskRunResultRepository taskRunResultRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TaskOnboardingChildTableLock childTableLock;
    private final TaskOnboardingContextCodec contextCodec;
    private final ObjectMapper objectMapper;

    public TaskOnboardingCleanupService(
            TaskConfigRepository taskConfigRepository,
            TaskResultRepository taskResultRepository,
            TaskRunRepository taskRunRepository,
            TaskRunResultRepository taskRunResultRepository,
            NamedParameterJdbcTemplate jdbcTemplate,
            TaskOnboardingChildTableLock childTableLock,
            TaskOnboardingContextCodec contextCodec,
            ObjectMapper objectMapper) {
        this.taskConfigRepository = taskConfigRepository;
        this.taskResultRepository = taskResultRepository;
        this.taskRunRepository = taskRunRepository;
        this.taskRunResultRepository = taskRunResultRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.childTableLock = childTableLock;
        this.contextCodec = contextCodec;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int deleteResultValidation(Long taskConfigId, String validationRunId) {
        TaskConfig task = loadTaskForUpdate(taskConfigId);
        TaskOnboardingContext context = contextCodec.read(task);
        requireExactValue(context.getResultValidationRunId(), validationRunId, "result validation run ID");
        List<Long> expectedIds = requireDistinctIds(context.getResultValidationIds(), "result validation IDs");
        String marker = "RESULT_VALIDATION:" + validationRunId;
        MapSqlParameterSource parameters = parameters(taskConfigId, marker)
                .addValue("resultIds", expectedIds);

        childTableLock.lockForCleanup();
        jdbcTemplate.queryForList(LOCK_RESULT_ROWS, parameters, Long.class);
        List<TaskResult> results = taskResultRepository
                .findByTaskConfigIdAndSourceDescriptionOrderByIdAsc(taskConfigId, marker);
        List<Long> actualIds = results.stream().map(TaskResult::getId).toList();
        if (!expectedIds.equals(actualIds)
                || results.stream().anyMatch(result -> !taskConfigId.equals(result.getTaskConfigId())
                        || !marker.equals(result.getSourceDescription()))) {
            throw new IllegalStateException("Marked result validation rows do not match onboarding context");
        }
        results.forEach(result -> requireValidationMetadata(result.getResultContent(), marker));
        if (taskRunResultRepository.countByTaskResultIdIn(expectedIds) != 0) {
            throw new IllegalStateException("Linked result validation rows cannot be deleted");
        }

        int deleted = jdbcTemplate.update(DELETE_RESULT_ROWS, parameters);
        if (deleted != expectedIds.size()
                || !taskResultRepository
                        .findByTaskConfigIdAndSourceDescriptionOrderByIdAsc(taskConfigId, marker)
                        .isEmpty()) {
            throw new IllegalStateException("Result validation cleanup was incomplete");
        }
        return deleted;
    }

    @Transactional
    public int deleteBatchValidation(Long taskConfigId, Long taskRunId, String validationRunId) {
        TaskConfig task = loadTaskForUpdate(taskConfigId);
        TaskOnboardingContext context = contextCodec.read(task);
        requireExactValue(context.getBatchValidationMarker(), validationRunId, "batch validation run ID");
        if (taskRunId == null || !taskRunId.equals(context.getBatchValidationTaskRunId())) {
            throw new IllegalStateException("Batch validation task run does not match onboarding context");
        }
        List<Long> expectedResultIds = requireDistinctIds(
                context.getBatchValidationResultIds(), "batch validation result IDs");
        String marker = "BATCH_VALIDATION:" + validationRunId;
        MapSqlParameterSource parameters = parameters(taskConfigId, marker)
                .addValue("taskRunId", taskRunId)
                .addValue("resultIds", expectedResultIds);

        childTableLock.lockForCleanup();
        jdbcTemplate.queryForList(LOCK_BATCH_RESULT_ROWS, parameters, Long.class);
        jdbcTemplate.queryForList(LOCK_RUN_ROW, parameters, Long.class);
        jdbcTemplate.queryForList(LOCK_LINK_ROWS, parameters, Long.class);
        jdbcTemplate.queryForList(LOCK_EXECUTION_LOG_ROWS, parameters, Long.class);
        TaskRun run = requireBatchRun(taskConfigId, taskRunId, marker);
        requireValidationMetadata(run.getAiPromptJson(), marker);
        List<TaskRunResult> links = taskRunResultRepository.findByTaskRunIdOrderByIdAsc(taskRunId);
        List<Long> actualResultIds = links.stream()
                .map(TaskRunResult::getTaskResultId)
                .sorted()
                .toList();
        List<Long> sortedExpectedIds = expectedResultIds.stream().sorted().toList();
        if (!sortedExpectedIds.equals(actualResultIds)
                || links.stream().anyMatch(link -> !taskRunId.equals(link.getTaskRunId()))
                || taskRunResultRepository.countLinkedResultsForRunAndTask(
                        taskRunId, taskConfigId, sortedExpectedIds) != sortedExpectedIds.size()) {
            throw new IllegalStateException("Batch validation links do not match onboarding context");
        }

        jdbcTemplate.update(DELETE_EXECUTION_LOGS, parameters);
        int deletedLinks = jdbcTemplate.update(DELETE_RUN_LINKS, parameters);
        int deletedRuns = jdbcTemplate.update(DELETE_RUN, parameters);
        if (deletedLinks != links.size() || deletedRuns != 1) {
            throw new IllegalStateException("Batch validation cleanup affected an unexpected row count");
        }
        Integer remainingLogs = jdbcTemplate.queryForObject(
                COUNT_EXECUTION_LOGS, parameters, Integer.class);
        if (taskRunRepository.findByIdAndTaskConfigIdAndReason(taskRunId, taskConfigId, marker).isPresent()
                || !taskRunResultRepository.findByTaskRunIdOrderByIdAsc(taskRunId).isEmpty()
                || remainingLogs == null
                || remainingLogs != 0) {
            throw new IllegalStateException("Batch validation cleanup was incomplete");
        }
        return deletedRuns;
    }

    private TaskConfig loadTaskForUpdate(Long taskConfigId) {
        if (taskConfigId == null) {
            throw new IllegalArgumentException("Missing task configuration ID");
        }
        return taskConfigRepository.findByIdForUpdate(taskConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Task configuration does not exist"));
    }

    private TaskRun requireBatchRun(Long taskConfigId, Long taskRunId, String marker) {
        Optional<TaskRun> run = taskRunRepository.findByIdAndTaskConfigIdAndReason(
                taskRunId, taskConfigId, marker);
        if (run.isEmpty()
                || !taskConfigId.equals(run.get().getTaskConfigId())
                || !taskRunId.equals(run.get().getId())
                || !marker.equals(run.get().getReason())) {
            throw new IllegalStateException("Marked batch validation run does not match onboarding context");
        }
        return run.get();
    }

    private void requireValidationMetadata(String json, String marker) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new IllegalStateException("Validation metadata must be valid JSON", ex);
        }
        JsonNode validationRunId = root == null ? null : root.path("_meta").path("validationRunId");
        if (validationRunId == null
                || !validationRunId.isTextual()
                || !marker.equals(validationRunId.textValue())) {
            throw new IllegalStateException("Validation metadata does not contain the exact marker");
        }
    }

    private static List<Long> requireDistinctIds(List<Long> values, String label) {
        List<Long> ids = values == null ? List.of() : new ArrayList<>(values);
        if (ids.isEmpty() || ids.stream().anyMatch(id -> id == null)
                || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalStateException("Invalid " + label);
        }
        return List.copyOf(ids);
    }

    private static void requireExactValue(String expected, String actual, String label) {
        if (!StringUtils.hasText(expected) || !expected.equals(actual)) {
            throw new IllegalStateException("The " + label + " does not match onboarding context");
        }
    }

    private static MapSqlParameterSource parameters(Long taskConfigId, String marker) {
        return new MapSqlParameterSource()
                .addValue("taskConfigId", taskConfigId)
                .addValue("marker", marker);
    }
}
