package com.aitaskcenter.service;

import com.aitaskcenter.dto.GenerateTaskRunBatchRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRecordType;
import com.aitaskcenter.repository.ConnectionConfigRepository;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.service.onboarding.OnboardingStep;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskConfigService {
    private static final int TASK_DESCRIPTION_MAX_LENGTH = 2000;

    private final TaskConfigRepository repository;
    private final ProjectConfigRepository projectRepository;
    private final ConnectionConfigRepository connectionRepository;
    private final TaskResultRepository taskResultRepository;
    private final PythonWorkerClient pythonWorkerClient;
    private final JdbcTemplate jdbcTemplate;

    // 方法：TaskConfigService
    public TaskConfigService(
            TaskConfigRepository repository,
            ProjectConfigRepository projectRepository,
            ConnectionConfigRepository connectionRepository,
            TaskResultRepository taskResultRepository,
            PythonWorkerClient pythonWorkerClient,
            JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.connectionRepository = connectionRepository;
        this.taskResultRepository = taskResultRepository;
        this.pythonWorkerClient = pythonWorkerClient;
        this.jdbcTemplate = jdbcTemplate;
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
        copyBasicFields(input, task);
        task.setHandlerKey(null);
        task.setExecutorType(null);
        task.setExecutorId(null);
        task.setOnboardingStep(OnboardingStep.TARGET_SELECTION.name());
        task.setOnboardingStatus("ACTIVE");
        task.setOnboardingContext("{}");
        return repository.save(task);
    }

    // 方法：generateResults
    public Map<String, Object> generateResults(Long id, boolean overwrite) {
        return generateResults(id, overwrite, TaskRecordType.FORMAL, null);
    }

    // 方法：generateResults
    public Map<String, Object> generateResults(
            Long id, boolean overwrite, String recordType, Integer limit) {
        if (id == null) {
            throw new IllegalArgumentException("缺少任务配置 ID");
        }
        TaskConfig task = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务配置不存在"));
        if (task.getDatabaseConfigId() == null || !connectionRepository.existsById(task.getDatabaseConfigId())) {
            throw new IllegalArgumentException("任务配置未关联有效数据库");
        }
        if (!StringUtils.hasText(task.getSelectedTables())) {
            throw new IllegalArgumentException("任务配置未选择来源表");
        }
        if (!List.of(TaskRecordType.VALIDATION_CURRENT, TaskRecordType.FORMAL).contains(recordType)) {
            throw new IllegalArgumentException("任务结果记录类型无效");
        }
        if (limit != null && (limit < 1 || limit > 20)) {
            throw new IllegalArgumentException("验证结果数量需在 1 到 20 之间");
        }
        return pythonWorkerClient.generateTaskResults(id, overwrite, recordType, limit);
    }

    @Transactional
    // 方法：generateRunBatches
    public Map<String, Object> generateRunBatches(Long id, GenerateTaskRunBatchRequest request) {
        archiveCurrentValidationRuns(id);
        return generateRunBatches(
                id,
                request,
                TaskRecordType.FORMAL,
                TaskRecordType.FORMAL,
                null);
    }

    @Transactional
    public Map<String, Object> generateValidationRunBatch(Long id, GenerateTaskRunBatchRequest request) {
        archiveCurrentValidationRuns(id);
        return generateRunBatches(
                id,
                request,
                TaskRecordType.FORMAL,
                TaskRecordType.VALIDATION_CURRENT,
                1);
    }

    private Map<String, Object> generateRunBatches(
            Long id,
            GenerateTaskRunBatchRequest request,
            String candidateResultRecordType,
            String runRecordType,
            Integer maxRunCount) {
        if (id == null) {
            throw new IllegalArgumentException("缺少任务配置 ID");
        }
        TaskConfig task = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务配置不存在"));
        if (request == null) {
            request = new GenerateTaskRunBatchRequest();
        }
        int batchSize = request.getBatchSize() == null ? 50 : request.getBatchSize();
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("每个批次数量需在 1 到 1000 之间");
        }
        if (!StringUtils.hasText(task.getHandlerKey())
                || !StringUtils.hasText(task.getExecutorType())
                || !StringUtils.hasText(task.getExecutorId())) {
            throw new IllegalArgumentException("任务处理器或模型调用通道尚未就绪");
        }
        TaskExecutionTargetResolver.ResolvedTarget executionTarget =
                new TaskExecutionTargetResolver.ResolvedTarget(
                        task.getHandlerKey().trim(),
                        task.getExecutorType().trim(),
                        task.getExecutorId().trim());
        String namePrefix = StringUtils.hasText(request.getTaskNamePrefix())
                ? request.getTaskNamePrefix().trim()
                : task.getTaskName();
        Set<String> statuses = buildBatchStatuses(request.getIncludeFailed());

        List<TaskResult> candidateResults = taskResultRepository
                .findByTaskConfigIdAndRecordTypeAndStatusInOrderByIdAsc(
                        id, candidateResultRecordType, statuses);
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
            if (maxRunCount != null && batchIndex > maxRunCount) {
                break;
            }
            int end = Math.min(start + batchSize, candidateResults.size());
            List<TaskResult> chunk = candidateResults.subList(start, end);
            String taskRunName = namePrefix + " - 批次 " + batchIndex;
            String promptJson = pythonWorkerClient.buildBatchPrompt(
                    executionTarget.handlerKey(),
                    task.getId(),
                    taskRunName,
                    chunk.stream().map(TaskResult::getId).toList());
            Long taskRunId = insertTaskRunBatch(
                    task, taskRunName, executionTarget, chunk.size(), promptJson, runRecordType, now);
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
            TaskExecutionTargetResolver.ResolvedTarget executionTarget,
            int resultCount,
            String promptJson,
            String recordType,
            OffsetDateTime now) {
        return jdbcTemplate.queryForObject(
                """
                insert into tb_task_run (
                    created_at,
                    updated_at,
                    task_name,
                    task_config_id,
                    project_id,
                    handler_key,
                    executor_type,
                    executor_id,
                    database_config_id,
                    selected_tables,
                    status,
                    reason,
                    log_path,
                    run_log,
                    ai_prompt_json,
                    ai_response_json,
                    record_type
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """,
                Long.class,
                now,
                now,
                taskRunName,
                task.getId(),
                task.getProjectId(),
                executionTarget.handlerKey(),
                executionTarget.executorType(),
                executionTarget.executorId(),
                task.getDatabaseConfigId(),
                task.getSelectedTables(),
                "PENDING",
                "由任务结果生成执行批次",
                "",
                "批次已创建，包含 " + resultCount + " 条任务结果。",
                promptJson,
                "",
                recordType);
    }

    private void archiveCurrentValidationRuns(Long taskConfigId) {
        jdbcTemplate.update(
                """
                update tb_task_run
                set record_type = ?, updated_at = ?
                where task_config_id = ? and record_type = ?
                """,
                TaskRecordType.VALIDATION_HISTORY,
                OffsetDateTime.now(),
                taskConfigId,
                TaskRecordType.VALIDATION_CURRENT);
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

    @Transactional
    // 方法：update
    public TaskConfig update(Long id, TaskConfig input) {
        TaskConfig task = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务配置不存在"));
        copyBasicFields(input, task);
        return repository.save(task);
    }

    // 方法：delete
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("缺少任务配置 ID");
        }
        repository.deleteById(id);
    }

    private void copyBasicFields(TaskConfig input, TaskConfig target) {
        target.setTaskName(require(input.getTaskName(), "请填写任务名称"));
        Long projectId = input.getProjectId();
        if (projectId == null || !projectRepository.existsById(projectId)) {
            throw new IllegalArgumentException("请选择有效项目");
        }
        target.setProjectId(projectId);
        Long databaseConfigId = input.getDatabaseConfigId();
        if (databaseConfigId != null && !connectionRepository.existsById(databaseConfigId)) {
            throw new IllegalArgumentException("关联数据库不存在");
        }
        target.setDatabaseConfigId(databaseConfigId);
        target.setTaskDesc(requireTaskDescription(input.getTaskDesc()));
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

    private static String requireTaskDescription(String value) {
        String description = require(value, "请填写任务描述");
        if (description.length() > TASK_DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("任务描述不能超过 2000 个字符");
        }
        return description;
    }

    // 方法：clean
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
