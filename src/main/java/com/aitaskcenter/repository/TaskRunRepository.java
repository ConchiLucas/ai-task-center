package com.aitaskcenter.repository;

import com.aitaskcenter.model.TaskRun;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {
    List<TaskRun> findAllByOrderByCreatedAtDesc();
}
