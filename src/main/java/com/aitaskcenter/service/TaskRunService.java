package com.aitaskcenter.service;

import com.aitaskcenter.dto.CreateTaskRunRequest;
import com.aitaskcenter.dto.PythonWorkerStartRequest;
import com.aitaskcenter.dto.PythonWorkerTaskRunItem;
import com.aitaskcenter.dto.StartTaskRunRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskExecutionLog;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.model.TaskRunResult;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskExecutionLogRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskRunService {
    private final TaskRunRepository repository;
    private final TaskConfigRepository taskConfigRepository;
    private final ProjectConfigRepository projectRepository;
    private final TaskResultRepository taskResultRepository;
    private final TaskRunResultRepository taskRunResultRepository;
    private final TaskExecutionLogRepository taskExecutionLogRepository;
    private final PythonWorkerClient pythonWorkerClient;
    private final TaskRunPromptBuilder taskRunPromptBuilder;

    public TaskRunService(
            TaskRunRepository repository,
            TaskConfigRepository taskConfigRepository,
            ProjectConfigRepository projectRepository,
            TaskResultRepository taskResultRepository,
            TaskRunResultRepository taskRunResultRepository,
            TaskExecutionLogRepository taskExecutionLogRepository,
            PythonWorkerClient pythonWorkerClient,
            TaskRunPromptBuilder taskRunPromptBuilder) {
        this.repository = repository;
        this.taskConfigRepository = taskConfigRepository;
        this.projectRepository = projectRepository;
        this.taskResultRepository = taskResultRepository;
        this.taskRunResultRepository = taskRunResultRepository;
        this.taskExecutionLogRepository = taskExecutionLogRepository;
        this.pythonWorkerClient = pythonWorkerClient;
        this.taskRunPromptBuilder = taskRunPromptBuilder;
    }

    // 方法：list
    public List<TaskRun> list(String taskName, Long projectId, String cliId, String status) {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .filter(item -> !StringUtils.hasText(taskName)
                        || item.getTaskName().toLowerCase(Locale.ROOT).contains(taskName.trim().toLowerCase(Locale.ROOT)))
                .filter(item -> projectId == null || projectId.equals(item.getProjectId()))
                .filter(item -> !StringUtils.hasText(cliId) || cliId.trim().equals(item.getCliId()))
                .filter(item -> !StringUtils.hasText(status) || status.trim().equals(item.getStatus()))
                .toList();
    }

    @Transactional
    // 方法：create
    public TaskRun create(CreateTaskRunRequest request) {
        Long taskConfigId = request.getTaskConfigId();
        if (taskConfigId == null) {
            throw new IllegalArgumentException("请选择任务配置");
        }
        TaskConfig config = taskConfigRepository.findById(taskConfigId)
                .orElseThrow(() -> new IllegalArgumentException("任务配置不存在"));
        TaskRun run = new TaskRun();
        run.setTaskConfigId(config.getId());
        run.setTaskName(defaultText(request.getTaskName(), config.getTaskName()));
        run.setProjectId(config.getProjectId());
        run.setCliId(config.getCliId());
        run.setDatabaseConfigId(config.getDatabaseConfigId());
        run.setSelectedTables(config.getSelectedTables());
        run.setStatus("PENDING");
        run.setReason("");
        run.setLogPath("");
        run.setRunLog("任务已创建，等待执行。");
        return repository.save(run);
    }

    @Transactional
    // 方法：retry
    public TaskRun retry(Long id) {
        TaskRun run = get(id);
        if ("RUNNING".equals(run.getStatus())) {
            throw new IllegalArgumentException("执行中的任务不能重试");
        }
        run.setStatus("PENDING");
        run.setReason("等待再次执行");
        return repository.save(run);
    }

    @Transactional
    // 方法：cancel
    public TaskRun cancel(Long id) {
        TaskRun run = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务记录不存在"));
        if (!List.of("PENDING", "QUEUED", "RETRY_WAIT", "RUNNING").contains(run.getStatus())) {
            throw new IllegalArgumentException("只有待执行、排队中、等待重试或执行中的任务可以取消");
        }
        run.setStatus("CANCELLED");
        run.setReason("手动取消");
        run.setRunLog(appendLog(run.getRunLog(), "任务已手动取消。"));
        run.setNextRetryAt(null);
        run.setLeaseUntil(null);
        run.setHeartbeatAt(null);
        run.setClaimToken(null);
        run.setWorkerId(null);
        return repository.save(run);
    }

    // 方法：get
    public TaskRun get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务记录不存在"));
    }

    // 方法：detail
    public Map<String, Object> detail(Long id) {
        TaskRun run = get(id);
        List<TaskRunResult> links = taskRunResultRepository.findByTaskRunIdOrderByIdAsc(id);
        List<TaskResult> results = loadLinkedResults(links);
        ensureBatchPromptJson(run, results);
        List<TaskExecutionLog> executions = ensureLegacyExecution(run);
        return Map.of(
                "taskRun", run,
                "links", links,
                "taskResults", results,
                "executions", executions);
    }

    // 方法：delete
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("缺少任务记录 ID");
        }
        repository.deleteById(id);
    }

    // 方法：batchDelete
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要删除的任务记录");
        }
        repository.deleteAllById(ids);
    }

    @Transactional
    // 方法：startExecution
    public Map<String, Object> startExecution(StartTaskRunRequest request) {
        List<Long> ids = request.getTaskRunIds();
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要执行的任务记录");
        }
        String cliId = require(request.getCliId(), "请选择执行 CLI");
        String executionMode = StringUtils.hasText(request.getExecutionMode()) ? request.getExecutionMode().trim() : "thread";
        if (!"thread".equals(executionMode)) {
            throw new IllegalArgumentException("PostgreSQL 队列第一版仅支持多线程调度");
        }
        int workerCount = request.getWorkerCount() == null ? 1 : request.getWorkerCount();
        if (workerCount < 1 || workerCount > 8) {
            throw new IllegalArgumentException("线程数量需在 1 到 8 之间");
        }

        List<TaskRun> requestedRuns = repository.findAllById(ids);
        if (requestedRuns.size() != ids.size()) {
            throw new IllegalArgumentException("部分任务记录不存在");
        }
        List<TaskRun> manageableRuns = requestedRuns.stream()
                .filter(run -> isStartOrConcurrencyAdjustableStatus(run.getStatus()))
                .toList();
        int skippedCount = requestedRuns.size() - manageableRuns.size();
        if (manageableRuns.isEmpty()) {
            throw new IllegalArgumentException("没有可执行或可调整并发的任务，成功任务已被过滤");
        }

        Set<String> activeDispatchGroupIds = manageableRuns.stream()
                .filter(run -> isActiveQueueStatus(run.getStatus()))
                .map(TaskRun::getDispatchGroupId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Map<Long, TaskRun> adjustableRunsById = new HashMap<>();
        manageableRuns.stream()
                .filter(run -> isActiveQueueStatus(run.getStatus()))
                .forEach(run -> adjustableRunsById.put(run.getId(), run));
        if (!activeDispatchGroupIds.isEmpty()) {
            repository.findByDispatchGroupIdIn(activeDispatchGroupIds).stream()
                    .filter(run -> isActiveQueueStatus(run.getStatus()))
                    .forEach(run -> adjustableRunsById.put(run.getId(), run));
        }
        List<TaskRun> adjustableRuns = new ArrayList<>(adjustableRunsById.values());
        for (TaskRun run : adjustableRuns) {
            run.setRequestedWorkerCount(workerCount);
            if (!"RUNNING".equals(run.getStatus())) {
                run.setCliId(cliId);
                run.setExecutionMode(executionMode);
            }
        }
        repository.saveAll(adjustableRuns);

        List<TaskRun> runs = manageableRuns.stream()
                .filter(run -> isExecutableStatus(run.getStatus()))
                .toList();
        if (runs.isEmpty()) {
            return Map.of(
                    "accepted", true,
                    "mode", "postgres-queue",
                    "queuedCount", 0,
                    "adjustedCount", adjustableRuns.size(),
                    "skippedCount", skippedCount,
                    "workerCount", workerCount,
                    "message", "已将调度组并发上限调整为 " + workerCount);
        }
        List<Long> executableIds = runs.stream().map(TaskRun::getId).toList();
        Map<Long, List<TaskRunResult>> linksByRunId = taskRunResultRepository
                .findByTaskRunIdInOrderByTaskRunIdAscIdAsc(executableIds)
                .stream()
                .collect(Collectors.groupingBy(TaskRunResult::getTaskRunId, HashMap::new, Collectors.toList()));
        String dispatchGroupId = UUID.randomUUID().toString();
        OffsetDateTime queuedAt = OffsetDateTime.now();
        List<TaskExecutionLog> queuedExecutions = new ArrayList<>();
        List<TaskRunResult> queuedLinks = new ArrayList<>();
        for (TaskRun run : runs) {
            List<TaskRunResult> links = linksByRunId.getOrDefault(run.getId(), List.of());
            if (!links.isEmpty()) {
                ensureBatchPromptJson(run, loadLinkedResults(links));
            }
            int previousAttempts = Math.max(
                    run.getAttemptNo() == null ? 0 : run.getAttemptNo(),
                    Math.toIntExact(taskExecutionLogRepository.countByTaskRunId(run.getId())));
            run.setCliId(cliId);
            run.setStatus("QUEUED");
            run.setStartTime(null);
            run.setEndTime(null);
            run.setDurationSeconds(null);
            run.setReason("已进入 PostgreSQL 执行队列");
            run.setAiResponseJson("");
            run.setRunLog("等待 Python Worker 领取，第 " + (previousAttempts + 1) + " 次执行待开始。");
            run.setExecutionMode(executionMode);
            run.setRequestedWorkerCount(workerCount);
            run.setDispatchGroupId(dispatchGroupId);
            run.setAttemptNo(previousAttempts);
            run.setMaxAttempts(previousAttempts + 3);
            run.setNextRetryAt(queuedAt);
            run.setLeaseUntil(null);
            run.setClaimToken(null);
            run.setWorkerId(null);
            run.setHeartbeatAt(null);
            run.setExpectedResultCount(links.size());
            queuedExecutions.add(createQueuedExecutionLog(run, executionMode, workerCount));
            for (TaskRunResult link : links) {
                link.setStatus("PENDING");
                link.setErrorMessage("");
                queuedLinks.add(link);
            }
        }
        repository.saveAll(runs);
        taskRunResultRepository.saveAll(queuedLinks);
        taskExecutionLogRepository.saveAll(queuedExecutions);
        return Map.of(
                "accepted", true,
                "mode", "postgres-queue",
                "dispatchGroupId", dispatchGroupId,
                "queuedCount", runs.size(),
                "adjustedCount", adjustableRuns.size(),
                "queuedTaskRunIds", executableIds,
                "skippedCount", skippedCount,
                "workerCount", workerCount,
                "message", "任务已进入 PostgreSQL 队列");
    }

    // 方法：processLinkedResults
    private Map<String, Object> processLinkedResults(
            TaskRun run,
            List<TaskRunResult> links,
            String cliId,
            int workerCount,
            TaskExecutionLog execution) {
        List<TaskResult> results = loadLinkedResults(links);
        ensureBatchPromptJson(run, results);
        for (TaskRunResult link : links) {
            link.setStatus("RUNNING");
            link.setErrorMessage("");
        }
        taskRunResultRepository.saveAll(links);
        try {
            Map<String, Object> response = pythonWorkerClient.processTaskRunBatch(run.getId(), cliId);
            syncRunResultLinks(links);
            int failedCount = numberValue(response.get("failedCount"));
            int successCount = numberValue(response.get("successCount"));
            Object aiResponseJson = response.get("aiResponseJson");
            if (aiResponseJson instanceof String text) {
                run.setAiResponseJson(text);
                execution.setAiResponseJson(text);
            }
            OffsetDateTime end = OffsetDateTime.now();
            run.setEndTime(end);
            run.setDurationSeconds(durationSeconds(run.getStartTime(), end));
            execution.setEndTime(end);
            execution.setDurationSeconds(durationSeconds(execution.getStartTime(), end));
            if (failedCount > 0) {
                run.setStatus("FAILED");
                run.setReason("批次执行完成，失败 " + failedCount + " 条");
            } else {
                run.setStatus("SUCCESS");
                run.setReason("批次执行成功");
            }
            execution.setStatus(run.getStatus());
            execution.setReason(run.getReason());
            execution.setRunLog(appendLog(execution.getRunLog(), "批量处理任务结果完成：成功 "
                    + successCount + " 条，失败 " + failedCount + " 条。"));
            run.setRunLog("最近执行：第 " + execution.getAttemptNo() + " 次，" + run.getReason() + "。");
            repository.save(run);
            taskExecutionLogRepository.save(execution);
            return response;
        } catch (Exception ex) {
            for (TaskRunResult link : links) {
                link.setStatus("FAILED");
                link.setErrorMessage(limit(ex.getMessage(), 4000));
            }
            taskRunResultRepository.saveAll(links);
            OffsetDateTime end = OffsetDateTime.now();
            run.setStatus("FAILED");
            run.setEndTime(end);
            run.setDurationSeconds(durationSeconds(run.getStartTime(), end));
            run.setReason(limit(ex.getMessage(), 1000));
            execution.setStatus("FAILED");
            execution.setEndTime(end);
            execution.setDurationSeconds(durationSeconds(execution.getStartTime(), end));
            execution.setReason(limit(ex.getMessage(), 1000));
            execution.setRunLog(appendLog(execution.getRunLog(), "批量处理任务结果失败：" + ex.getMessage()));
            run.setRunLog("最近执行：第 " + execution.getAttemptNo() + " 次，失败。");
            repository.save(run);
            taskExecutionLogRepository.save(execution);
            return Map.of(
                    "accepted", false,
                    "taskRunId", run.getId(),
                    "failedCount", links.size(),
                    "message", ex.getMessage());
        }
    }

    // 方法：loadLinkedResults
    private List<TaskResult> loadLinkedResults(List<TaskRunResult> links) {
        Map<Long, TaskResult> resultsById = taskResultRepository
                .findAllById(links.stream().map(TaskRunResult::getTaskResultId).toList())
                .stream()
                .collect(Collectors.toMap(TaskResult::getId, Function.identity()));
        return links.stream()
                .map(link -> resultsById.get(link.getTaskResultId()))
                .filter(result -> result != null)
                .toList();
    }

    // 方法：ensureBatchPromptJson
    private void ensureBatchPromptJson(TaskRun run, List<TaskResult> results) {
        if (StringUtils.hasText(run.getAiPromptJson()) || results.isEmpty()) {
            return;
        }
        String promptJson = taskRunPromptBuilder.buildBatchPromptJson(
                new TaskRunPromptBuilder.BatchPromptContext(
                        run.getTaskName(),
                        run.getCliId(),
                        run.getSelectedTables()),
                results);
        run.setAiPromptJson(promptJson);
        repository.save(run);
    }

    // 方法：ensureLegacyExecution
    private List<TaskExecutionLog> ensureLegacyExecution(TaskRun run) {
        List<TaskExecutionLog> executions = taskExecutionLogRepository.findByTaskRunIdOrderByAttemptNoDesc(run.getId());
        if (!executions.isEmpty() || (!StringUtils.hasText(run.getRunLog()) && !StringUtils.hasText(run.getAiResponseJson()))) {
            return executions;
        }
        TaskExecutionLog legacy = new TaskExecutionLog();
        legacy.setTaskRunId(run.getId());
        legacy.setAttemptNo(1);
        legacy.setCliId(defaultText(run.getCliId(), "未配置 CLI"));
        legacy.setExecutionMode("legacy");
        legacy.setWorkerCount(1);
        legacy.setStatus(defaultText(run.getStatus(), "PENDING"));
        legacy.setStartTime(run.getStartTime());
        legacy.setEndTime(run.getEndTime());
        legacy.setDurationSeconds(run.getDurationSeconds());
        legacy.setReason(run.getReason());
        legacy.setRunLog(run.getRunLog());
        legacy.setAiPromptJson(run.getAiPromptJson());
        legacy.setAiResponseJson(run.getAiResponseJson());
        taskExecutionLogRepository.save(legacy);
        return taskExecutionLogRepository.findByTaskRunIdOrderByAttemptNoDesc(run.getId());
    }

    // 方法：createQueuedExecutionLog
    private TaskExecutionLog createQueuedExecutionLog(TaskRun run, String executionMode, int workerCount) {
        TaskExecutionLog execution = new TaskExecutionLog();
        execution.setTaskRunId(run.getId());
        execution.setAttemptNo((run.getAttemptNo() == null ? 0 : run.getAttemptNo()) + 1);
        execution.setCliId(run.getCliId());
        execution.setExecutionMode(executionMode);
        execution.setWorkerCount(workerCount);
        execution.setStatus("QUEUED");
        execution.setReason("等待 Python Worker 领取");
        execution.setRunLog("第 " + execution.getAttemptNo() + " 次执行已进入 PostgreSQL 队列。\n"
                + "CLI=" + run.getCliId() + "，模式=" + executionMode + "，并发上限=" + workerCount + "。");
        execution.setAiPromptJson(run.getAiPromptJson());
        execution.setAiResponseJson("");
        return execution;
    }

    // 方法：nextAttemptNo
    private int nextAttemptNo(Long taskRunId) {
        return Math.toIntExact(taskExecutionLogRepository.countByTaskRunId(taskRunId) + 1);
    }

    // 方法：syncRunResultLinks
    private void syncRunResultLinks(List<TaskRunResult> links) {
        Map<Long, TaskResult> resultsById = taskResultRepository.findAllById(
                        links.stream().map(TaskRunResult::getTaskResultId).toList())
                .stream()
                .collect(Collectors.toMap(TaskResult::getId, Function.identity()));
        for (TaskRunResult link : links) {
            TaskResult result = resultsById.get(link.getTaskResultId());
            if (result == null) {
                link.setStatus("FAILED");
                link.setErrorMessage("任务结果不存在");
            } else {
                link.setStatus(result.getStatus());
                link.setErrorMessage(result.getErrorMessage());
            }
        }
        taskRunResultRepository.saveAll(links);
    }

    // 方法：toWorkerTaskRunItem
    private PythonWorkerTaskRunItem toWorkerTaskRunItem(TaskRun run) {
        PythonWorkerTaskRunItem item = new PythonWorkerTaskRunItem();
        item.setId(run.getId());
        item.setTaskName(run.getTaskName());
        item.setTaskConfigId(run.getTaskConfigId());
        item.setProjectId(run.getProjectId());
        item.setDatabaseConfigId(run.getDatabaseConfigId());
        item.setSelectedTables(run.getSelectedTables());
        return item;
    }

    // 方法：numberValue
    private static int numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    // 方法：durationSeconds
    private static int durationSeconds(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return Math.toIntExact(Math.max(0, Duration.between(start, end).getSeconds()));
    }

    // 方法：isExecutableStatus
    private static boolean isExecutableStatus(String status) {
        return "PENDING".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    // 方法：isActiveQueueStatus
    private static boolean isActiveQueueStatus(String status) {
        return "QUEUED".equals(status) || "RETRY_WAIT".equals(status) || "RUNNING".equals(status);
    }

    // 方法：isStartOrConcurrencyAdjustableStatus
    private static boolean isStartOrConcurrencyAdjustableStatus(String status) {
        return isExecutableStatus(status) || isActiveQueueStatus(status);
    }

    // 方法：limit
    private static String limit(String value, int maxLength) {
        String text = value == null ? "" : value;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    // 方法：defaultText
    private String defaultText(String value, String fallback) {
        String result = StringUtils.hasText(value) ? value.trim() : fallback;
        if (!StringUtils.hasText(result)) {
            throw new IllegalArgumentException("请填写任务名称");
        }
        return result;
    }

    // 方法：appendLog
    private static String appendLog(String current, String line) {
        if (!StringUtils.hasText(current)) {
            return line;
        }
        return current + "\n" + line;
    }

    // 方法：require
    private static String require(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
