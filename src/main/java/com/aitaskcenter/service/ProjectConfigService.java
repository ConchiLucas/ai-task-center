package com.aitaskcenter.service;

import com.aitaskcenter.model.ProjectConfig;
import com.aitaskcenter.repository.ProjectConfigRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProjectConfigService {
    private final ProjectConfigRepository repository;

    public ProjectConfigService(ProjectConfigRepository repository) {
        this.repository = repository;
    }

    public List<ProjectConfig> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public ProjectConfig create(ProjectConfig input) {
        String name = clean(input.getProjectName());
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("请输入项目名称");
        }
        repository.findByProjectName(name).ifPresent(existing -> {
            throw new IllegalArgumentException("项目名称已存在");
        });
        ProjectConfig project = new ProjectConfig();
        project.setProjectName(name);
        project.setProjectDesc(clean(input.getProjectDesc()));
        project.setUserName(defaultText(input.getUserName(), "local"));
        return repository.save(project);
    }

    @Transactional
    public ProjectConfig update(Long id, ProjectConfig input) {
        ProjectConfig project = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        String name = clean(input.getProjectName());
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        repository.findByProjectName(name)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("项目名称已存在");
                });
        project.setProjectName(name);
        project.setProjectDesc(clean(input.getProjectDesc()));
        return repository.save(project);
    }

    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("缺少项目 ID");
        }
        repository.deleteById(id);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
