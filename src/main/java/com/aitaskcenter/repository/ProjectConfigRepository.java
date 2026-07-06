package com.aitaskcenter.repository;

import com.aitaskcenter.model.ProjectConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectConfigRepository extends JpaRepository<ProjectConfig, Long> {
    Optional<ProjectConfig> findByProjectName(String projectName);

    List<ProjectConfig> findAllByOrderByCreatedAtDesc();
}
