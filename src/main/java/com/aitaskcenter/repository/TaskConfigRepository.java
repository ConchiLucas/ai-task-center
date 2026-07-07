package com.aitaskcenter.repository;

import com.aitaskcenter.model.TaskConfig;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskConfigRepository extends JpaRepository<TaskConfig, Long> {
    List<TaskConfig> findAllByOrderByCreatedAtDesc();

    List<TaskConfig> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
