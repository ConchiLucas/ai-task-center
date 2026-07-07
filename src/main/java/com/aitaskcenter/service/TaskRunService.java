package com.aitaskcenter.service;

import com.aitaskcenter.dto.CreateTaskRunRequest;
import com.aitaskcenter.dto.PythonWorkerStartRequest;
import com.aitaskcenter.dto.PythonWorkerTaskRunItem;
import com.aitaskcenter.dto.StartTaskRunRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskRunService {
    private final TaskRunRepository repository;
    private final TaskConfigRepository taskConfigRepository;
    private final ProjectConfigRepository projectRepository;
    private final PythonWorkerClient pythonWorkerClient;

    public TaskRunService(
            TaskRunRepository repository,
            TaskConfigRepository taskConfigRepository,
            ProjectConfigRepository projectRepository,
            PythonWorkerClient pythonWorkerClient) {
        this.repository = repository;
        this.taskConfigRepository = taskConfigRepository;
        this.projectRepository = projectRepository;
        this.pythonWorkerClient = pythonWorkerClient;
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
        TaskRun source = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务记录不存在"));
        TaskRun run = new TaskRun();
        run.setTaskConfigId(source.getTaskConfigId());
        run.setTaskName(source.getTaskName());
        run.setProjectId(source.getProjectId());
        run.setCliId(source.getCliId());
        run.setDatabaseConfigId(source.getDatabaseConfigId());
        run.setSelectedTables(source.getSelectedTables());
        run.setStatus("PENDING");
        run.setReason("由任务 " + id + " 重试生成");
        run.setLogPath("");
        run.setRunLog("重试任务已创建，等待执行。");
        return repository.save(run);
    }

    @Transactional
    // 方法：cancel
    public TaskRun cancel(Long id) {
        TaskRun run = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务记录不存在"));
        if (!"PENDING".equals(run.getStatus()) && !"RUNNING".equals(run.getStatus())) {
            throw new IllegalArgumentException("只有待执行或执行中的任务可以取消");
        }
        run.setStatus("CANCELLED");
        run.setReason("手动取消");
        run.setRunLog(appendLog(run.getRunLog(), "任务已手动取消。"));
        return repository.save(run);
    }

    // 方法：get
    public TaskRun get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务记录不存在"));
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
        int workerCount = request.getWorkerCount() == null ? 1 : request.getWorkerCount();
        if (workerCount < 1 || workerCount > 32) {
            throw new IllegalArgumentException("线程/进程数量需在 1 到 32 之间");
        }

        List<TaskRun> runs = repository.findAllById(ids);
        if (runs.size() != ids.size()) {
            throw new IllegalArgumentException("部分任务记录不存在");
        }
        for (TaskRun run : runs) {
            if (!"PENDING".equals(run.getStatus()) && !"FAILED".equals(run.getStatus()) && !"CANCELLED".equals(run.getStatus())) {
                throw new IllegalArgumentException("只有待执行、失败或已取消任务可以开始执行");
            }
            run.setCliId(cliId);
            run.setStatus("RUNNING");
            run.setStartTime(OffsetDateTime.now());
            run.setEndTime(null);
            run.setDurationSeconds(null);
            run.setReason("已提交 Python Worker");
            run.setRunLog(appendLog(run.getRunLog(), "已提交 Python Worker，CLI=" + cliId + "，模式=" + executionMode + "，并发数=" + workerCount + "。"));
        }
        repository.saveAll(runs);

        PythonWorkerStartRequest workerRequest = new PythonWorkerStartRequest();
        workerRequest.setCliId(cliId);
        workerRequest.setExecutionMode(executionMode);
        workerRequest.setWorkerCount(workerCount);
        workerRequest.setTaskRuns(runs.stream().map(this::toWorkerTaskRunItem).toList());
        Map<String, Object> response = pythonWorkerClient.startExecution(workerRequest);
        for (TaskRun run : runs) {
            run.setRunLog(appendLog(run.getRunLog(), "Python Worker 已接收执行请求。"));
        }
        repository.saveAll(runs);
        return response;
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
