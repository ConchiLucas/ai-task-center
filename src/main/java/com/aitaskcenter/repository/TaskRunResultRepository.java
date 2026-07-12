package com.aitaskcenter.repository;

import com.aitaskcenter.model.TaskRunResult;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRunResultRepository extends JpaRepository<TaskRunResult, Long> {
    // 方法：findByTaskRunIdOrderByIdAsc
    List<TaskRunResult> findByTaskRunIdOrderByIdAsc(Long taskRunId);

    // 方法：findByTaskRunIdInOrderByTaskRunIdAscIdAsc
    List<TaskRunResult> findByTaskRunIdInOrderByTaskRunIdAscIdAsc(Collection<Long> taskRunIds);

    // 方法：findByTaskResultIdInOrderByIdDesc
    List<TaskRunResult> findByTaskResultIdInOrderByIdDesc(Collection<Long> taskResultIds);

    long countByTaskResultIdIn(Collection<Long> taskResultIds);

    @Query("""
            select count(link)
            from TaskRunResult link, TaskResult result
            where link.taskRunId = :taskRunId
              and link.taskResultId = result.id
              and result.taskConfigId = :taskConfigId
              and link.taskResultId in :taskResultIds
            """)
    long countLinkedResultsForRunAndTask(
            @Param("taskRunId") Long taskRunId,
            @Param("taskConfigId") Long taskConfigId,
            @Param("taskResultIds") Collection<Long> taskResultIds);
}
