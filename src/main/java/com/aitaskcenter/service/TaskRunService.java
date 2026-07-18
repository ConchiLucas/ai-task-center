package com.aitaskcenter.service;

import com.aitaskcenter.dto.CreateTaskRunRequest;
import com.aitaskcenter.dto.PythonWorkerTaskRunItem;
import com.aitaskcenter.dto.StartTaskRunRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskExecutionLog;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRecordType;
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
    private final TaskExecutionTargetResolver executionTargetResolver;

    public TaskRunService(
            TaskRunRepository repository,
            TaskConfigRepository taskConfigRepository,
            ProjectConfigRepository projectRepository,
            TaskResultRepository taskResultRepository,
            TaskRunResultRepository taskRunResultRepository,
            TaskExecutionLogRepository taskExecutionLogRepository,
            PythonWorkerClient pythonWorkerClient,
            TaskExecutionTargetResolver executionTargetResolver) {
        this.repository = repository;
        this.taskConfigRepository = taskConfigRepository;
        this.projectRepository = projectRepository;
        this.taskResultRepository = taskResultRepository;
        this.taskRunResultRepository = taskRunResultRepository;
        this.taskExecutionLogRepository = taskExecutionLogRepository;
        this.pythonWorkerClient = pythonWorkerClient;
        this.executionTargetResolver = executionTargetResolver;
    }

    // 方法：list
    public List<TaskRun> list(
            String taskName,
            Long projectId,
            Long taskConfigId,
            String executorType,
            String executorId,
            String status,
            String recordType) {
        String recordTypeFilter = TaskRecordType.normalizeFilter(recordType);
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .filter(item -> recordTypeFilter == null || recordTypeFilter.equals(item.getRecordType()))
                .filter(item -> !StringUtils.hasText(taskName)
                        || item.getTaskName().toLowerCase(Locale.ROOT).contains(taskName.trim().toLowerCase(Locale.ROOT)))
                .filter(item -> projectId == null || projectId.equals(item.getProjectId()))
                .filter(item -> taskConfigId == null || taskConfigId.equals(item.getTaskConfigId()))
                .filter(item -> matchesExecutionTarget(item, executorType, executorId))
                .filter(item -> !StringUtils.hasText(status) || status.trim().equals(item.getStatus()))
                .toList();
    }

    private boolean matchesExecutionTarget(TaskRun run, String executorType, String executorId) {
        if (!StringUtils.hasText(executorType) && !StringUtils.hasText(executorId)) {
            return true;
        }
        TaskExecutionTargetResolver.ResolvedTarget target = executionTargetResolver.require(
                run.getHandlerKey(),
                run.getExecutorType(),
                run.getExecutorId());
        return (!StringUtils.hasText(executorType) || executorType.trim().equals(target.executorType()))
                && (!StringUtils.hasText(executorId) || executorId.trim().equals(target.executorId()));
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
        TaskExecutionTargetResolver.ResolvedTarget target = executionTargetResolver.require(
                config.getHandlerKey(), config.getExecutorType(), config.getExecutorId());
        run.setHandlerKey(target.handlerKey());
        run.setExecutorType(target.executorType());
        run.setExecutorId(target.executorId());
        run.setDatabaseConfigId(config.getDatabaseConfigId());
        run.setSelectedTables(config.getSelectedTables());
        run.setStatus("PENDING");
        run.setRecordType(TaskRecordType.FORMAL);
        run.setReason("");
        run.setLogPath("");
        run.setRunLog("任务已创建，等待执行。");
        return repository.save(run);
    }

    @Transactional
    // 方法：retry
    public TaskRun retry(Long id) {
        TaskRun run = get(id);
        requireMutableRun(run);
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
        requireMutableRun(run);
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
        List<TaskExecutionLog> executions = taskExecutionLogRepository
                .findByTaskRunIdOrderByAttemptNoDesc(run.getId());
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
        TaskRun run = get(id);
        requireMutableRun(run);
        repository.delete(run);
    }

    // 方法：batchDelete
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要删除的任务记录");
        }
        List<TaskRun> runs = repository.findAllById(ids);
        if (runs.size() != ids.size()) {
            throw new IllegalArgumentException("部分任务记录不存在");
        }
        runs.forEach(this::requireMutableRun);
        repository.deleteAll(runs);
    }

    @Transactional
    // 方法：startExecution
    public Map<String, Object> startExecution(StartTaskRunRequest request) {
        List<Long> ids = request.getTaskRunIds();
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要执行的任务记录");
        }
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
        requestedRuns.forEach(this::requireMutableRun);
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

    // 方法：requireMutableRun
    private void requireMutableRun(TaskRun run) {
        if (TaskRecordType.VALIDATION_HISTORY.equals(run.getRecordType())) {
            throw new IllegalArgumentException("历史验证批次仅供查看，不能执行、重试、取消或删除");
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
        TaskExecutionTargetResolver.ResolvedTarget target = executionTargetResolver.require(
                run.getHandlerKey(), run.getExecutorType(), run.getExecutorId());
        String promptJson = pythonWorkerClient.buildBatchPrompt(
                target.handlerKey(),
                run.getTaskConfigId(),
                run.getTaskName(),
                results.stream().map(TaskResult::getId).toList());
        run.setAiPromptJson(promptJson);
        repository.save(run);
    }

    // 方法：createQueuedExecutionLog
    private TaskExecutionLog createQueuedExecutionLog(TaskRun run, String executionMode, int workerCount) {
        TaskExecutionLog execution = new TaskExecutionLog();
        execution.setTaskRunId(run.getId());
        execution.setAttemptNo((run.getAttemptNo() == null ? 0 : run.getAttemptNo()) + 1);
        execution.setHandlerKey(run.getHandlerKey());
        execution.setExecutorType(run.getExecutorType());
        execution.setExecutorId(run.getExecutorId());
        execution.setExecutorLabel(run.getExecutorId());
        execution.setExecutionMode(executionMode);
        execution.setWorkerCount(workerCount);
        execution.setStatus("QUEUED");
        execution.setReason("等待 Python Worker 领取");
        execution.setRunLog("第 " + execution.getAttemptNo() + " 次执行已进入 PostgreSQL 队列。\n"
                + "调用通道=" + defaultText(run.getExecutorType(), "CLI") + "/"
                + defaultText(run.getExecutorId(), "未配置")
                + "，模式=" + executionMode + "，并发上限=" + workerCount + "。");
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
