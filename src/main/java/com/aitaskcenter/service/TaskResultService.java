package com.aitaskcenter.service;

import com.aitaskcenter.dto.BatchProcessTaskResultRequest;
import com.aitaskcenter.dto.PageResult;
import com.aitaskcenter.dto.TaskRunReference;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRecordType;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskExecutionLog;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.model.TaskRunResult;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskExecutionLogRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskResultService {
    private final TaskResultRepository repository;
    private final ProjectConfigRepository projectRepository;
    private final TaskRunResultRepository taskRunResultRepository;
    private final TaskRunRepository taskRunRepository;
    private final TaskConfigRepository taskConfigRepository;
    private final TaskExecutionLogRepository taskExecutionLogRepository;
    private final PythonWorkerClient pythonWorkerClient;
    private final TaskExecutionTargetResolver executionTargetResolver;

    // 方法：TaskResultService
    public TaskResultService(
            TaskResultRepository repository,
            ProjectConfigRepository projectRepository,
            TaskRunResultRepository taskRunResultRepository,
            TaskRunRepository taskRunRepository,
            TaskConfigRepository taskConfigRepository,
            TaskExecutionLogRepository taskExecutionLogRepository,
            PythonWorkerClient pythonWorkerClient,
            TaskExecutionTargetResolver executionTargetResolver) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.taskRunResultRepository = taskRunResultRepository;
        this.taskRunRepository = taskRunRepository;
        this.taskConfigRepository = taskConfigRepository;
        this.taskExecutionLogRepository = taskExecutionLogRepository;
        this.pythonWorkerClient = pythonWorkerClient;
        this.executionTargetResolver = executionTargetResolver;
    }

    // 方法：list
    public PageResult<TaskResult> list(
            int page,
            int pageSize,
            String resultName,
            Long projectId,
            Long taskConfigId,
            String status,
            String recordType) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 500));
        Specification<TaskResult> specification = Specification.where(null);
        String recordTypeFilter = TaskRecordType.normalizeFilter(recordType);
        if (recordTypeFilter != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("recordType"), recordTypeFilter));
        }
        if (StringUtils.hasText(resultName)) {
            String keyword = "%" + resultName.trim().toLowerCase() + "%";
            specification = specification.and((root, query, builder) ->
                    builder.like(builder.lower(root.get("resultName")), keyword));
        }
        if (projectId != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("projectId"), projectId));
        }
        if (taskConfigId != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("taskConfigId"), taskConfigId));
        }
        if (StringUtils.hasText(status)) {
            String expectedStatus = status.trim();
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), expectedStatus));
        }
        Page<TaskResult> resultPage = repository.findAll(
                specification,
                PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<TaskResult> results = resultPage.getContent();
        attachRelatedTaskRuns(results);
        // 列表不传输体积较大的 AI 提示词和响应正文，详情接口按需加载完整内容。
        results.forEach(result -> result.setResultContent(""));
        return new PageResult<>(results, resultPage.getTotalElements(), safePage, safePageSize);
    }

    // 方法：get
    public TaskResult get(Long id) {
        TaskResult result = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务结果不存在"));
        attachRelatedTaskRuns(List.of(result));
        return result;
    }

    // 方法：process
    @Transactional
    public Map<String, Object> process(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("缺少任务结果 ID");
        }
        TaskResult result = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务结果不存在"));
        requireExecutable(result);
        resolveResultTarget(result);
        if (TaskRecordType.FORMAL.equals(result.getRecordType())) {
            if (!List.of("PENDING", "FAILED").contains(result.getStatus())) {
                throw new IllegalArgumentException("只有待处理或失败的正式结果可以执行");
            }
            if (findActiveQueuedResultIds(List.of(id)).contains(id)) {
                throw new IllegalArgumentException("任务结果已进入执行队列");
            }
            return enqueueTaskResults(List.of(result), 1, 0);
        }
        return pythonWorkerClient.processTaskResult(id);
    }

    @Transactional
    // 方法：processBatch
    public Map<String, Object> processBatch(BatchProcessTaskResultRequest request) {
        List<Long> ids = request.getTaskResultIds();
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要批量执行的任务结果");
        }
        int workerCount = request.getWorkerCount() == null ? 4 : request.getWorkerCount();
        if (workerCount < 1 || workerCount > 32) {
            throw new IllegalArgumentException("并发数量需在 1 到 32 之间");
        }
        List<TaskResult> requestedResults = repository.findAllById(ids);
        if (requestedResults.size() != ids.size()) {
            throw new IllegalArgumentException("部分任务结果不存在");
        }
        Set<String> recordTypes = requestedResults.stream()
                .map(TaskResult::getRecordType)
                .collect(Collectors.toSet());
        if (recordTypes.size() != 1) {
            throw new IllegalArgumentException("正式结果和验证结果不能混合批量执行");
        }
        String recordType = recordTypes.iterator().next();
        if (TaskRecordType.VALIDATION_HISTORY.equals(recordType)) {
            throw new IllegalArgumentException("历史验证结果仅供查看");
        }
        Set<Long> activeResultIds = findActiveQueuedResultIds(ids);
        List<TaskResult> results = requestedResults.stream()
                .filter(result -> List.of("PENDING", "FAILED").contains(result.getStatus()))
                .filter(result -> !activeResultIds.contains(result.getId()))
                .toList();
        int skippedCount = requestedResults.size() - results.size();
        if (results.isEmpty()) {
            throw new IllegalArgumentException("没有可执行结果，成功结果或已入队结果已被过滤");
        }
        results.forEach(this::resolveResultTarget);
        if (TaskRecordType.VALIDATION_CURRENT.equals(recordType)) {
            return pythonWorkerClient.processTaskResults(
                    results.stream().map(TaskResult::getId).toList(), workerCount);
        }
        return enqueueTaskResults(results, workerCount, skippedCount);
    }

    // 方法：findActiveQueuedResultIds
    private Set<Long> findActiveQueuedResultIds(List<Long> taskResultIds) {
        List<TaskRunResult> links = taskRunResultRepository.findByTaskResultIdInOrderByIdDesc(taskResultIds);
        if (links.isEmpty()) {
            return Set.of();
        }
        Set<Long> activeRunIds = taskRunRepository.findAllById(
                        links.stream().map(TaskRunResult::getTaskRunId).distinct().toList())
                .stream()
                .filter(run -> List.of("QUEUED", "RUNNING", "RETRY_WAIT").contains(run.getStatus()))
                .map(TaskRun::getId)
                .collect(Collectors.toSet());
        return links.stream()
                .filter(link -> activeRunIds.contains(link.getTaskRunId()))
                .map(TaskRunResult::getTaskResultId)
                .collect(Collectors.toSet());
    }

    // 方法：enqueueTaskResults
    private Map<String, Object> enqueueTaskResults(
            List<TaskResult> results,
            int workerCount,
            int skippedCount) {
        String dispatchGroupId = UUID.randomUUID().toString();
        OffsetDateTime queuedAt = OffsetDateTime.now();
        Map<ResultQueueGroup, List<TaskResult>> groupedResults = results.stream()
                .collect(Collectors.groupingBy(
                        result -> ResultQueueGroup.from(result, resolveResultTarget(result)),
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<Long, String> taskConfigNames = taskConfigRepository.findAllById(
                        results.stream().map(TaskResult::getTaskConfigId).filter(id -> id != null).distinct().toList())
                .stream()
                .collect(Collectors.toMap(TaskConfig::getId, TaskConfig::getTaskName));
        List<TaskRun> runs = new ArrayList<>();
        List<List<TaskResult>> runResults = new ArrayList<>();
        int runSequence = 1;
        for (Map.Entry<ResultQueueGroup, List<TaskResult>> entry : groupedResults.entrySet()) {
            List<List<TaskResult>> partitions = partitionForConcurrency(entry.getValue(), workerCount);
            for (List<TaskResult> partition : partitions) {
                ResultQueueGroup group = entry.getKey();
                String configName = taskConfigNames.getOrDefault(group.taskConfigId(), "任务结果");
                String runName = configName + " - 异步批次 " + runSequence++;
                TaskRun run = createQueuedTaskRun(
                        group,
                        partition,
                        runName,
                        workerCount,
                        dispatchGroupId,
                        queuedAt);
                runs.add(run);
                runResults.add(partition);
            }
        }
        taskRunRepository.saveAll(runs);

        List<TaskRunResult> links = new ArrayList<>();
        List<TaskExecutionLog> executionLogs = new ArrayList<>();
        for (int index = 0; index < runs.size(); index++) {
            TaskRun run = runs.get(index);
            for (TaskResult result : runResults.get(index)) {
                TaskRunResult link = new TaskRunResult();
                link.setTaskRunId(run.getId());
                link.setTaskResultId(result.getId());
                link.setStatus("PENDING");
                link.setErrorMessage("");
                links.add(link);
            }
            executionLogs.add(createQueuedExecutionLog(run, workerCount));
        }
        taskRunResultRepository.saveAll(links);
        taskExecutionLogRepository.saveAll(executionLogs);
        for (TaskResult result : results) {
            result.setStatus("PENDING");
            result.setSummary("已进入异步执行队列");
            result.setErrorMessage("");
            result.setCompletedAt(null);
        }
        repository.saveAll(results);
        return Map.of(
                "accepted", true,
                "mode", "postgres-queue",
                "dispatchGroupId", dispatchGroupId,
                "queuedResultCount", results.size(),
                "createdRunCount", runs.size(),
                "skippedCount", skippedCount,
                "workerCount", workerCount);
    }

    // 方法：partitionForConcurrency
    private List<List<TaskResult>> partitionForConcurrency(List<TaskResult> results, int workerCount) {
        int partitionCount = Math.min(workerCount, results.size());
        List<List<TaskResult>> partitions = new ArrayList<>();
        for (int index = 0; index < partitionCount; index++) {
            partitions.add(new ArrayList<>());
        }
        for (int index = 0; index < results.size(); index++) {
            partitions.get(index % partitionCount).add(results.get(index));
        }
        return partitions;
    }

    // 方法：createQueuedTaskRun
    private TaskRun createQueuedTaskRun(
            ResultQueueGroup group,
            List<TaskResult> results,
            String runName,
            int workerCount,
            String dispatchGroupId,
            OffsetDateTime queuedAt) {
        TaskRun run = new TaskRun();
        run.setTaskName(runName);
        run.setTaskConfigId(group.taskConfigId());
        run.setProjectId(group.projectId());
        run.setHandlerKey(group.handlerKey());
        run.setExecutorType(group.executorType());
        run.setExecutorId(group.executorId());
        run.setDatabaseConfigId(group.databaseConfigId());
        run.setSelectedTables(group.sourceTables());
        run.setStatus("QUEUED");
        run.setReason("任务结果已进入 PostgreSQL 异步队列");
        run.setRunLog("等待 Python Worker 领取，第 1 次执行待开始。");
        run.setAiPromptJson(pythonWorkerClient.buildBatchPrompt(
                group.handlerKey(),
                group.taskConfigId(),
                runName,
                results.stream().map(TaskResult::getId).toList()));
        run.setAiResponseJson("");
        run.setExecutionMode("thread");
        run.setRequestedWorkerCount(workerCount);
        run.setDispatchGroupId(dispatchGroupId);
        run.setAttemptNo(0);
        run.setMaxAttempts(3);
        run.setNextRetryAt(queuedAt);
        run.setExpectedResultCount(results.size());
        return run;
    }

    // 方法：createQueuedExecutionLog
    private TaskExecutionLog createQueuedExecutionLog(TaskRun run, int workerCount) {
        TaskExecutionLog execution = new TaskExecutionLog();
        execution.setTaskRunId(run.getId());
        execution.setAttemptNo(1);
        execution.setHandlerKey(run.getHandlerKey());
        execution.setExecutorType(run.getExecutorType());
        execution.setExecutorId(run.getExecutorId());
        execution.setExecutorLabel(run.getExecutorId());
        execution.setExecutionMode("thread");
        execution.setWorkerCount(workerCount);
        execution.setStatus("QUEUED");
        execution.setReason("等待 Python Worker 领取");
        execution.setRunLog("异步任务结果批次已进入 PostgreSQL 队列。");
        execution.setAiPromptJson(run.getAiPromptJson());
        execution.setAiResponseJson("");
        return execution;
    }

    // 记录：ResultQueueGroup
    private record ResultQueueGroup(
            Long taskConfigId,
            Long projectId,
            Long databaseConfigId,
            String sourceTables,
            String handlerKey,
            String executorType,
            String executorId) {
        // 方法：from
        private static ResultQueueGroup from(TaskResult result, ResolvedResultTarget target) {
            return new ResultQueueGroup(
                    result.getTaskConfigId(),
                    result.getProjectId(),
                    result.getDatabaseConfigId(),
                    result.getSourceTables(),
                    target.handlerKey(),
                    target.executorType(),
                    target.executorId());
        }
    }

    @Transactional
    // 方法：create
    public TaskResult create(TaskResult input) {
        TaskResult result = new TaskResult();
        copyAndValidate(input, result);
        if (result.getParsedAt() == null) {
            result.setParsedAt(OffsetDateTime.now());
        }
        return repository.save(result);
    }

    @Transactional
    // 方法：update
    public TaskResult update(Long id, TaskResult input) {
        TaskResult result = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务结果不存在"));
        copyAndValidate(input, result);
        if ("SUCCESS".equals(result.getStatus()) || "FAILED".equals(result.getStatus())) {
            result.setCompletedAt(input.getCompletedAt() == null ? OffsetDateTime.now() : input.getCompletedAt());
        }
        return repository.save(result);
    }

    // 方法：delete
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("缺少任务结果 ID");
        }
        TaskResult result = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务结果不存在"));
        if (TaskRecordType.VALIDATION_HISTORY.equals(result.getRecordType())) {
            throw new IllegalArgumentException("历史验证结果仅供查看，不能删除");
        }
        repository.delete(result);
    }

    // 方法：copyAndValidate
    private void copyAndValidate(TaskResult input, TaskResult target) {
        target.setResultName(require(input.getResultName(), "请填写结果名称"));
        Long projectId = input.getProjectId();
        if (projectId == null || !projectRepository.existsById(projectId)) {
            throw new IllegalArgumentException("请选择有效项目");
        }
        target.setProjectId(projectId);
        target.setTaskRunId(input.getTaskRunId());
        target.setTaskConfigId(input.getTaskConfigId());
        target.setHandlerKey(clean(input.getHandlerKey()));
        target.setExecutorType(clean(input.getExecutorType()));
        target.setExecutorId(clean(input.getExecutorId()));
        target.setDatabaseConfigId(input.getDatabaseConfigId());
        target.setSourceTables(clean(input.getSourceTables()));
        target.setSourceDescription(clean(input.getSourceDescription()));
        target.setStatus(defaultText(input.getStatus(), "PENDING"));
        target.setRecordType(TaskRecordType.FORMAL);
        target.setSummary(clean(input.getSummary()));
        target.setResultContent(input.getResultContent() == null ? "" : input.getResultContent());
        target.setErrorMessage(clean(input.getErrorMessage()));
        target.setParsedAt(input.getParsedAt());
        target.setCompletedAt(input.getCompletedAt());
    }

    // 方法：attachRelatedTaskRuns
    private void attachRelatedTaskRuns(List<TaskResult> results) {
        if (results.isEmpty()) {
            return;
        }
        List<Long> resultIds = results.stream().map(TaskResult::getId).toList();
        List<TaskRunResult> links = new ArrayList<>();
        int chunkSize = 1000;
        for (int start = 0; start < resultIds.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, resultIds.size());
            links.addAll(taskRunResultRepository.findByTaskResultIdInOrderByIdDesc(resultIds.subList(start, end)));
        }
        if (links.isEmpty()) {
            return;
        }
        Map<Long, TaskRun> runsById = taskRunRepository.findAllById(
                        links.stream().map(TaskRunResult::getTaskRunId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(TaskRun::getId, Function.identity()));
        Map<Long, List<TaskRunReference>> referencesByResultId = new HashMap<>();
        for (TaskRunResult link : links) {
            TaskRun run = runsById.get(link.getTaskRunId());
            if (run == null) {
                continue;
            }
            if (!TaskRecordType.FORMAL.equals(run.getRecordType())) {
                continue;
            }
            referencesByResultId.computeIfAbsent(link.getTaskResultId(), ignored -> new ArrayList<>())
                    .add(new TaskRunReference(run.getId(), run.getTaskName(), run.getStatus()));
        }
        for (TaskResult result : results) {
            result.setRelatedTaskRuns(referencesByResultId.getOrDefault(result.getId(), List.of()));
        }
    }

    // 方法：require
    private static String require(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static void requireExecutable(TaskResult result) {
        if (TaskRecordType.VALIDATION_HISTORY.equals(result.getRecordType())) {
            throw new IllegalArgumentException("历史验证结果仅供查看，不能执行");
        }
    }

    private ResolvedResultTarget resolveResultTarget(TaskResult result) {
        TaskExecutionTargetResolver.ResolvedTarget resolved = executionTargetResolver.require(
                result.getHandlerKey(), result.getExecutorType(), result.getExecutorId());
        return new ResolvedResultTarget(
                resolved.handlerKey(),
                resolved.executorType(),
                resolved.executorId());
    }

    private record ResolvedResultTarget(
            String handlerKey,
            String executorType,
            String executorId) {}

    // 方法：clean
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    // 方法：defaultText
    private static String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
