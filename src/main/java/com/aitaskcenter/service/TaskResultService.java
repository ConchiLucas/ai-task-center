package com.aitaskcenter.service;

import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskResultService {
    private final TaskResultRepository repository;
    private final ProjectConfigRepository projectRepository;

    // 方法：TaskResultService
    public TaskResultService(TaskResultRepository repository, ProjectConfigRepository projectRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
    }

    // 方法：list
    public List<TaskResult> list(String resultName, Long projectId, String status) {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .filter(item -> !StringUtils.hasText(resultName)
                        || item.getResultName().toLowerCase(Locale.ROOT).contains(resultName.trim().toLowerCase(Locale.ROOT)))
                .filter(item -> projectId == null || projectId.equals(item.getProjectId()))
                .filter(item -> !StringUtils.hasText(status) || status.trim().equals(item.getStatus()))
                .toList();
    }

    // 方法：get
    public TaskResult get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务结果不存在"));
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
