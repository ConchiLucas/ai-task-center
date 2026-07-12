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

    @Query(value = """
            select cast(to_jsonb(link_row) as text)
            from tb_task_run_result link_row
            join tb_task_run run_row on run_row.id = link_row.task_run_id
            where run_row.task_config_id = :taskConfigId
            order by link_row.id asc
            """, nativeQuery = true)
    List<String> findFingerprintRowsByTaskConfigId(@Param("taskConfigId") Long taskConfigId);

    @Query(value = """
            select cast(to_jsonb(link_row) as text)
            from tb_task_run_result link_row
            join tb_task_run run_row on run_row.id = link_row.task_run_id
            where run_row.task_config_id = :taskConfigId
              and link_row.task_run_id <> :excludedRunId
            order by link_row.id asc
            """, nativeQuery = true)
    List<String> findFingerprintRowsByTaskConfigIdAndTaskRunIdNot(
            @Param("taskConfigId") Long taskConfigId,
            @Param("excludedRunId") Long excludedRunId);
}
