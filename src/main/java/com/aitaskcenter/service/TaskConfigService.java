package com.aitaskcenter.service;

import com.aitaskcenter.dto.GenerateTaskRunBatchRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.repository.ConnectionConfigRepository;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.service.onboarding.OnboardingStatus;
import com.aitaskcenter.service.onboarding.OnboardingStep;
import com.aitaskcenter.service.onboarding.TaskOnboardingContext;
import com.aitaskcenter.service.onboarding.TaskOnboardingResetService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskConfigService {
    private static final Pattern ONBOARDING_GENERATION_ID = Pattern.compile("[0-9a-f]{64}");
    private static final String DEFAULT_BATCH_REASON = "由任务结果生成执行批次";
    private static final String FORMAL_GENERATION_REASON_PREFIX = "FORMAL_GENERATION:";
    private final TaskConfigRepository repository;
    private final ProjectConfigRepository projectRepository;
    private final ConnectionConfigRepository connectionRepository;
    private final TaskResultRepository taskResultRepository;
    private final PythonWorkerClient pythonWorkerClient;
    private final TaskRunPromptBuilder taskRunPromptBuilder;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskOnboardingResetService onboardingResetService;

    // 方法：TaskConfigService
    public TaskConfigService(
            TaskConfigRepository repository,
            ProjectConfigRepository projectRepository,
            ConnectionConfigRepository connectionRepository,
            TaskResultRepository taskResultRepository,
            PythonWorkerClient pythonWorkerClient,
            TaskRunPromptBuilder taskRunPromptBuilder,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this(repository, projectRepository, connectionRepository, taskResultRepository,
                pythonWorkerClient, taskRunPromptBuilder, jdbcTemplate, objectMapper, null);
    }

    @Autowired
    public TaskConfigService(
            TaskConfigRepository repository,
            ProjectConfigRepository projectRepository,
            ConnectionConfigRepository connectionRepository,
            TaskResultRepository taskResultRepository,
            PythonWorkerClient pythonWorkerClient,
            TaskRunPromptBuilder taskRunPromptBuilder,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TaskOnboardingResetService onboardingResetService) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.connectionRepository = connectionRepository;
        this.taskResultRepository = taskResultRepository;
        this.pythonWorkerClient = pythonWorkerClient;
        this.taskRunPromptBuilder = taskRunPromptBuilder;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.onboardingResetService = onboardingResetService;
    }

    // 方法：list
    public List<TaskConfig> list(Long projectId) {
        if (projectId == null) {
            return repository.findAllByOrderByCreatedAtDesc();
        }
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional
    // 方法：create
    public TaskConfig create(TaskConfig input) {
        TaskConfig task = new TaskConfig();
        copyAndValidate(input, task);
        return repository.save(task);
    }

    // 方法：generateResults
    public Map<String, Object> generateResults(Long id, boolean overwrite) {
        return generateResults(id, overwrite, null);
    }

    public Map<String, Object> generateResults(
            Long id, boolean overwrite, String onboardingGenerationId) {
        if (id == null) {
            throw new IllegalArgumentException("缺少任务配置 ID");
        }
        String generationId = clean(onboardingGenerationId);
        if (!generationId.isEmpty() && !ONBOARDING_GENERATION_ID.matcher(generationId).matches()) {
            throw new IllegalArgumentException("引导生成 ID 格式无效");
        }
        TaskConfig task = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务配置不存在"));
        if (task.getDatabaseConfigId() == null || !connectionRepository.existsById(task.getDatabaseConfigId())) {
            throw new IllegalArgumentException("任务配置未关联有效数据库");
        }
        if (!StringUtils.hasText(task.getSelectedTables())) {
            throw new IllegalArgumentException("任务配置未选择来源表");
        }
        return pythonWorkerClient.generateTaskResults(
                id, overwrite, generationId.isEmpty() ? null : generationId);
    }

    @Transactional
    // 方法：generateRunBatches
    public Map<String, Object> generateRunBatches(Long id, GenerateTaskRunBatchRequest request) {
        return generateRunBatches(id, request, null);
    }

    @Transactional
    public Map<String, Object> generateRunBatches(
            Long id, GenerateTaskRunBatchRequest request, String onboardingGenerationId) {
        return generateRunBatches(id, request, onboardingGenerationId, null);
    }

    @Transactional
    public Map<String, Object> generateRunBatches(
            Long id,
            GenerateTaskRunBatchRequest request,
            String onboardingGenerationId,
            List<Long> expectedResultIds) {
        if (id == null) {
            throw new IllegalArgumentException("缺少任务配置 ID");
        }
        String generationId = clean(onboardingGenerationId);
        if (!generationId.isEmpty() && !ONBOARDING_GENERATION_ID.matcher(generationId).matches()) {
            throw new IllegalArgumentException("引导生成 ID 格式无效");
        }
        TaskConfig task = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("任务配置不存在"));
        int batchSize = request.getBatchSize() == null ? 50 : request.getBatchSize();
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("每个批次数量需在 1 到 1000 之间");
        }
        String cliId = StringUtils.hasText(request.getCliId()) ? request.getCliId().trim() : task.getCliId();
        if (!StringUtils.hasText(cliId)) {
            throw new IllegalArgumentException("请选择执行 CLI");
        }
        String namePrefix = StringUtils.hasText(request.getTaskNamePrefix())
                ? request.getTaskNamePrefix().trim()
                : task.getTaskName();
        Set<String> statuses = buildBatchStatuses(request.getIncludeFailed());
        String reason = generationId.isEmpty()
                ? DEFAULT_BATCH_REASON
                : FORMAL_GENERATION_REASON_PREFIX + generationId;

        List<Long> exactExpectedIds = generationId.isEmpty()
                ? List.of()
                : expectedResultIds == null
                        ? taskResultRepository.findByTaskConfigIdAndStatusInOrderByIdAsc(id, statuses)
                                .stream().map(TaskResult::getId).sorted().toList()
                        : requireExpectedResultIds(expectedResultIds);
        if (!generationId.isEmpty() && exactExpectedIds.isEmpty()) {
            throw new IllegalStateException("Missing expected formal result IDs");
        }
        Map<String, Object> recovered = recoverGeneratedRunBatches(
                task.getId(), reason, generationId, batchSize, request.getIncludeFailed(), exactExpectedIds);
        if (recovered != null) {
            return recovered;
        }

        List<TaskResult> candidateResults = generationId.isEmpty()
                ? taskResultRepository.findByTaskConfigIdAndStatusInOrderByIdAsc(id, statuses)
                : exactCandidates(id, statuses, exactExpectedIds);
        if (candidateResults.isEmpty()) {
            return Map.of(
                    "createdRunCount", 0,
                    "linkedResultCount", 0,
                    "batchSize", batchSize,
                    "message", "没有符合条件的任务结果");
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<Object[]> linkRows = new ArrayList<>(candidateResults.size());
        int batchIndex = 1;
        for (int start = 0; start < candidateResults.size(); start += batchSize) {
            int end = Math.min(start + batchSize, candidateResults.size());
            List<TaskResult> chunk = candidateResults.subList(start, end);
            String taskRunName = namePrefix + " - 批次 " + batchIndex;
            String promptJson = taskRunPromptBuilder.buildBatchPromptJson(
                    new TaskRunPromptBuilder.BatchPromptContext(taskRunName, cliId, task.getSelectedTables()),
                    chunk);
            if (!generationId.isEmpty()) {
                promptJson = addGenerationMetadata(promptJson, generationId);
            }
            Long taskRunId = insertTaskRunBatch(
                    task, taskRunName, cliId, chunk.size(), promptJson, reason, now);
            for (TaskResult taskResult : chunk) {
                linkRows.add(new Object[]{now, now, taskRunId, taskResult.getId(), "PENDING", ""});
            }
            batchIndex++;
        }
        batchInsertTaskRunResultLinks(linkRows);
        return Map.of(
                "createdRunCount", batchIndex - 1,
                "linkedResultCount", linkRows.size(),
                "batchSize", batchSize,
                "includeFailed", Boolean.TRUE.equals(request.getIncludeFailed()));
    }

    // 方法：insertTaskRunBatch
    private Long insertTaskRunBatch(
            TaskConfig task,
            String taskRunName,
            String cliId,
            int resultCount,
            String promptJson,
            String reason,
            OffsetDateTime now) {
        return jdbcTemplate.queryForObject(
                """
                insert into tb_task_run (
                    created_at,
                    updated_at,
                    task_name,
                    task_config_id,
                    project_id,
                    cli_id,
                    database_config_id,
                    selected_tables,
                    status,
                    reason,
                    log_path,
                    run_log,
                    ai_prompt_json,
                    ai_response_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """,
                Long.class,
                now,
                now,
                taskRunName,
                task.getId(),
                task.getProjectId(),
                cliId,
                task.getDatabaseConfigId(),
                task.getSelectedTables(),
                "PENDING",
                reason,
                "",
                "批次已创建，包含 " + resultCount + " 条任务结果。",
                promptJson,
                "");
    }

    // 方法：batchInsertTaskRunResultLinks
    private void batchInsertTaskRunResultLinks(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                insert into tb_task_run_result (
                    created_at,
                    updated_at,
                    task_run_id,
                    task_result_id,
                    status,
                    error_message
                ) values (?, ?, ?, ?, ?, ?)
                """,
                rows);
    }

    private Map<String, Object> recoverGeneratedRunBatches(
            Long taskConfigId,
            String reason,
            String generationId,
            int batchSize,
            Boolean includeFailed,
            List<Long> expectedResultIds) {
        if (generationId.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> runs = jdbcTemplate.queryForList(
                """
                select id, ai_prompt_json
                from tb_task_run
                where task_config_id = ? and reason = ?
                order by id
                """,
                taskConfigId,
                reason);
        if (runs.isEmpty()) {
            return null;
        }
        for (Map<String, Object> run : runs) {
            requireGenerationMetadata(String.valueOf(run.get("ai_prompt_json")), generationId);
        }
        List<Long> linkedResultIds = jdbcTemplate.queryForList(
                """
                select result.id
                from tb_task_run_result link
                join tb_task_run run on run.id = link.task_run_id
                join tb_task_result result on result.id = link.task_result_id
                where run.task_config_id = ?
                  and run.reason = ?
                  and result.task_config_id = ?
                order by result.id
                """,
                Long.class,
                taskConfigId,
                reason,
                taskConfigId);
        if (!expectedResultIds.equals(linkedResultIds)) {
            throw new IllegalStateException("Recovered formal batches do not exactly cover expected results");
        }
        return Map.of(
                "createdRunCount", runs.size(),
                "linkedResultCount", linkedResultIds.size(),
                "batchSize", batchSize,
                "includeFailed", Boolean.TRUE.equals(includeFailed),
                "recovered", true);
    }

    private String addGenerationMetadata(String promptJson, String generationId) {
        try {
            JsonNode parsed = objectMapper.readTree(promptJson);
            if (!(parsed instanceof ObjectNode root)) {
                throw new IllegalArgumentException("批次提示词必须是 JSON 对象");
            }
            JsonNode existingMetadata = root.get("_meta");
            ObjectNode metadata;
            if (existingMetadata == null) {
                metadata = root.putObject("_meta");
            } else if (existingMetadata instanceof ObjectNode objectMetadata) {
                metadata = objectMetadata;
            } else {
                throw new IllegalArgumentException("批次提示词 _meta 必须是 JSON 对象");
            }
            metadata.put("onboardingGenerationId", generationId);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("批次提示词 JSON 无效", ex);
        }
    }

    private void requireGenerationMetadata(String promptJson, String generationId) {
        try {
            JsonNode metadataId = objectMapper.readTree(promptJson)
                    .path("_meta").path("onboardingGenerationId");
            if (!metadataId.isTextual() || !generationId.equals(metadataId.textValue())) {
                throw new IllegalStateException("Recovered formal batch metadata does not match the attempt");
            }
        } catch (JsonProcessingException | NullPointerException ex) {
            throw new IllegalStateException("Recovered formal batch metadata is invalid", ex);
        }
    }

    @Transactional
    // 方法：update
    public TaskConfig update(Long id, TaskConfig input) {
        TaskConfig task = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务配置不存在"));
        SemanticConfig previous = SemanticConfig.from(task);
        copyAndValidate(input, task);
        if (semanticConfigChanged(previous, task)) {
            task.setOnboardingStep(OnboardingStep.RESULT_CODE.name());
            task.setOnboardingStatus(OnboardingStatus.ACTIVE.name());
            task.setOnboardingContext(onboardingResetService == null
                    ? "{}"
                    : writeContext(onboardingResetService.prepareSemanticReset(id)));
        }
        return repository.save(task);
    }

    private List<TaskResult> exactCandidates(
            Long taskConfigId, Set<String> statuses, List<Long> expectedResultIds) {
        List<TaskResult> candidates = taskResultRepository.findAllById(expectedResultIds).stream()
                .sorted(java.util.Comparator.comparing(TaskResult::getId))
                .toList();
        if (candidates.size() != expectedResultIds.size()
                || candidates.stream().anyMatch(result -> !taskConfigId.equals(result.getTaskConfigId())
                        || !statuses.contains(result.getStatus()))) {
            throw new IllegalStateException("Formal batch candidates changed after Phase A");
        }
        return candidates;
    }

    private static List<Long> requireExpectedResultIds(List<Long> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Missing expected formal result IDs");
        }
        List<Long> sorted = values.stream().sorted().toList();
        if (sorted.stream().distinct().count() != sorted.size()) {
            throw new IllegalStateException("Expected formal result IDs are not unique");
        }
        return sorted;
    }

    private String writeContext(TaskOnboardingContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to persist reset onboarding context", ex);
        }
    }

    // 方法：delete
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("缺少任务配置 ID");
        }
        repository.deleteById(id);
    }

    // 方法：copyAndValidate
    private void copyAndValidate(TaskConfig input, TaskConfig target) {
        target.setTaskName(require(input.getTaskName(), "请填写任务名称"));
        Long projectId = input.getProjectId();
        if (projectId == null || !projectRepository.existsById(projectId)) {
            throw new IllegalArgumentException("请选择有效项目");
        }
        target.setProjectId(projectId);
        target.setCliId(require(input.getCliId(), "请选择默认执行工具"));
        Long databaseConfigId = input.getDatabaseConfigId();
        if (databaseConfigId != null && !connectionRepository.existsById(databaseConfigId)) {
            throw new IllegalArgumentException("关联数据库不存在");
        }
        target.setDatabaseConfigId(databaseConfigId);
        target.setTaskDesc(clean(input.getTaskDesc()));
        target.setSelectedTables(clean(input.getSelectedTables()));
    }

    // 方法：buildBatchStatuses
    private static Set<String> buildBatchStatuses(Boolean includeFailed) {
        Set<String> statuses = new HashSet<>();
        statuses.add("PENDING");
        if (Boolean.TRUE.equals(includeFailed)) {
            statuses.add("FAILED");
        }
        return statuses;
    }

    // 方法：require
    private static String require(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    // 方法：clean
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean semanticConfigChanged(SemanticConfig previous, TaskConfig current) {
        return !Objects.equals(previous.projectId(), current.getProjectId())
                || !Objects.equals(previous.cliId(), current.getCliId())
                || !Objects.equals(previous.databaseConfigId(), current.getDatabaseConfigId())
                || !selectedTablesEqual(previous.selectedTables(), current.getSelectedTables())
                || !clean(previous.taskDesc()).equals(clean(current.getTaskDesc()));
    }

    private boolean selectedTablesEqual(String left, String right) {
        String normalizedLeft = clean(left);
        String normalizedRight = clean(right);
        try {
            JsonNode leftJson = objectMapper.readTree(normalizedLeft);
            JsonNode rightJson = objectMapper.readTree(normalizedRight);
            if (leftJson != null && rightJson != null) {
                return leftJson.equals(rightJson);
            }
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            // Existing non-JSON values retain their prior string-comparison behavior.
        }
        return normalizedLeft.equals(normalizedRight);
    }

    private record SemanticConfig(
            Long projectId,
            String cliId,
            Long databaseConfigId,
            String selectedTables,
            String taskDesc) {
        private static SemanticConfig from(TaskConfig task) {
            return new SemanticConfig(
                    task.getProjectId(),
                    task.getCliId(),
                    task.getDatabaseConfigId(),
                    task.getSelectedTables(),
                    task.getTaskDesc());
        }
    }
}
