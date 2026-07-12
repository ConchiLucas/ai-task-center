package com.aitaskcenter.repository;

import com.aitaskcenter.model.TaskExecutionLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskExecutionLogRepository extends JpaRepository<TaskExecutionLog, Long> {
    // 方法：findByTaskRunIdOrderByAttemptNoDesc
    List<TaskExecutionLog> findByTaskRunIdOrderByAttemptNoDesc(Long taskRunId);

    // 方法：countByTaskRunId
    long countByTaskRunId(Long taskRunId);
}
