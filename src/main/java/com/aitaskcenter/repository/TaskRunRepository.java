package com.aitaskcenter.repository;

import com.aitaskcenter.model.TaskRun;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {
    List<TaskRun> findAllByOrderByCreatedAtDesc();

    List<TaskRun> findByTaskConfigIdAndReasonOrderByIdAsc(Long taskConfigId, String reason);

    // 方法：findByDispatchGroupIdIn
    List<TaskRun> findByDispatchGroupIdIn(Collection<String> dispatchGroupIds);
}
