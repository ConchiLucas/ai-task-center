package com.aitaskcenter.service;

import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.ConnectionConfigRepository;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskConfigService {
    private final TaskConfigRepository repository;
    private final ProjectConfigRepository projectRepository;
    private final ConnectionConfigRepository connectionRepository;

    public TaskConfigService(
            TaskConfigRepository repository,
            ProjectConfigRepository projectRepository,
            ConnectionConfigRepository connectionRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.connectionRepository = connectionRepository;
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

    @Transactional
    // 方法：update
    public TaskConfig update(Long id, TaskConfig input) {
        TaskConfig task = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务配置不存在"));
        copyAndValidate(input, task);
        return repository.save(task);
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
}
