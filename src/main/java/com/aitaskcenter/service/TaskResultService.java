package com.aitaskcenter.service;

import com.aitaskcenter.dto.BatchProcessTaskResultRequest;
import com.aitaskcenter.dto.TaskRunReference;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.model.TaskRunResult;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskResultService {
    private final TaskResultRepository repository;
    private final ProjectConfigRepository projectRepository;
    private final TaskRunResultRepository taskRunResultRepository;
    private final TaskRunRepository taskRunRepository;
    private final PythonWorkerClient pythonWorkerClient;

    // 方法：TaskResultService
    public TaskResultService(
            TaskResultRepository repository,
            ProjectConfigRepository projectRepository,
            TaskRunResultRepository taskRunResultRepository,
            TaskRunRepository taskRunRepository,
            PythonWorkerClient pythonWorkerClient) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.taskRunResultRepository = taskRunResultRepository;
        this.taskRunRepository = taskRunRepository;
        this.pythonWorkerClient = pythonWorkerClient;
    }

    // 方法：list
    public List<TaskResult> list(String resultName, Long projectId, Long taskConfigId, String status) {
        List<TaskResult> results = repository.findAllByOrderByCreatedAtDesc().stream()
                .filter(item -> !StringUtils.hasText(resultName)
                        || item.getResultName().toLowerCase(Locale.ROOT).contains(resultName.trim().toLowerCase(Locale.ROOT)))
                .filter(item -> projectId == null || projectId.equals(item.getProjectId()))
                .filter(item -> taskConfigId == null || taskConfigId.equals(item.getTaskConfigId()))
                .filter(item -> !StringUtils.hasText(status) || status.trim().equals(item.getStatus()))
                .toList();
        attachRelatedTaskRuns(results);
        return results;
    }

    // 方法：get
    public TaskResult get(Long id) {
        TaskResult result = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务结果不存在"));
        attachRelatedTaskRuns(List.of(result));
        return result;
    }

    // 方法：process
    public Map<String, Object> process(Long id, String cliId) {
        if (id == null) {
            throw new IllegalArgumentException("缺少任务结果 ID");
        }
        TaskResult result = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务结果不存在"));
        String effectiveCliId = StringUtils.hasText(cliId) ? cliId.trim() : result.getCliId();
        if (!StringUtils.hasText(effectiveCliId)) {
            throw new IllegalArgumentException("任务结果未配置执行 CLI");
        }
        return pythonWorkerClient.processTaskResult(id, effectiveCliId);
    }

    // 方法：processBatch
    public Map<String, Object> processBatch(BatchProcessTaskResultRequest request) {
        List<Long> ids = request.getTaskResultIds();
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要批量执行的任务结果");
        }
        String cliId = require(request.getCliId(), "请选择执行 CLI");
        int workerCount = request.getWorkerCount() == null ? 4 : request.getWorkerCount();
        if (workerCount < 1 || workerCount > 32) {
            throw new IllegalArgumentException("并发数量需在 1 到 32 之间");
        }
        List<TaskResult> results = repository.findAllById(ids);
        if (results.size() != ids.size()) {
            throw new IllegalArgumentException("部分任务结果不存在");
        }
        return pythonWorkerClient.processTaskResults(ids, cliId, workerCount);
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
        repository.deleteById(id);
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
        target.setCliId(clean(input.getCliId()));
        target.setDatabaseConfigId(input.getDatabaseConfigId());
        target.setSourceTables(clean(input.getSourceTables()));
        target.setSourceDescription(clean(input.getSourceDescription()));
        target.setStatus(defaultText(input.getStatus(), "PENDING"));
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

    // 方法：clean
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    // 方法：defaultText
    private static String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
