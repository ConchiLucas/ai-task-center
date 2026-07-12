package com.aitaskcenter.repository;

import com.aitaskcenter.model.TaskRunResult;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRunResultRepository extends JpaRepository<TaskRunResult, Long> {
    // 方法：findByTaskRunIdOrderByIdAsc
    List<TaskRunResult> findByTaskRunIdOrderByIdAsc(Long taskRunId);

    // 方法：findByTaskRunIdInOrderByTaskRunIdAscIdAsc
    List<TaskRunResult> findByTaskRunIdInOrderByTaskRunIdAscIdAsc(Collection<Long> taskRunIds);

    // 方法：findByTaskResultIdInOrderByIdDesc
    List<TaskRunResult> findByTaskResultIdInOrderByIdDesc(Collection<Long> taskResultIds);
}
