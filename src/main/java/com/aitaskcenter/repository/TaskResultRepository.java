package com.aitaskcenter.repository;

import com.aitaskcenter.model.TaskResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskResultRepository extends JpaRepository<TaskResult, Long> {
    List<TaskResult> findAllByOrderByCreatedAtDesc();
}
