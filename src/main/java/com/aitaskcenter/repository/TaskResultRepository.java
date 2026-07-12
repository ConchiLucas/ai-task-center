package com.aitaskcenter.repository;

import com.aitaskcenter.model.TaskResult;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskResultRepository extends JpaRepository<TaskResult, Long>, JpaSpecificationExecutor<TaskResult> {
    List<TaskResult> findAllByOrderByCreatedAtDesc();

    // 方法：findByTaskConfigIdOrderByIdAsc
    List<TaskResult> findByTaskConfigIdOrderByIdAsc(Long taskConfigId);

    List<TaskResult> findByTaskConfigIdAndSourceDescriptionOrderByIdAsc(
            Long taskConfigId, String sourceDescription);

    List<TaskResult> findByIdInAndTaskConfigIdAndSourceDescriptionOrderByIdAsc(
            Collection<Long> ids, Long taskConfigId, String sourceDescription);

    @Query("""
            select result
            from TaskResult result, TaskRunResult link, TaskRun run
            where result.id in :resultIds
              and link.taskResultId = result.id
              and link.taskRunId = run.id
              and run.id = :runId
              and run.taskConfigId = :taskConfigId
              and run.reason = :reason
            order by result.id asc
            """)
    List<TaskResult> findValidationRunResults(
            @Param("resultIds") Collection<Long> resultIds,
            @Param("runId") Long runId,
            @Param("taskConfigId") Long taskConfigId,
            @Param("reason") String reason);

    // 方法：findByTaskConfigIdAndStatusInOrderByIdAsc
    List<TaskResult> findByTaskConfigIdAndStatusInOrderByIdAsc(Long taskConfigId, Collection<String> statuses);

    @Query("""
            select result
            from TaskResult result
            where result.taskConfigId = :taskConfigId
              and result.status in :statuses
              and not exists (
                  select link.id
                  from TaskRunResult link, TaskRun run
                  where link.taskResultId = result.id
                    and link.taskRunId = run.id
                    and run.taskConfigId = :taskConfigId
                    and run.reason = :reason
              )
            order by result.id asc
            """)
    List<TaskResult> findUnlinkedForGeneration(
            @Param("taskConfigId") Long taskConfigId,
            @Param("statuses") Collection<String> statuses,
            @Param("reason") String reason);

    // 方法：findIdsByTaskConfigIdAndStatusIn
    @Query("select item.id from TaskResult item where item.taskConfigId = :taskConfigId and item.status in :statuses order by item.id asc")
    List<Long> findIdsByTaskConfigIdAndStatusIn(
            @Param("taskConfigId") Long taskConfigId,
            @Param("statuses") Collection<String> statuses);

    @Query(value = """
            select cast(to_jsonb(result_row) as text)
            from tb_task_result result_row
            where result_row.task_config_id = :taskConfigId
            order by result_row.id asc
            """, nativeQuery = true)
    List<String> findFingerprintRowsByTaskConfigId(@Param("taskConfigId") Long taskConfigId);

    @Query(value = """
            select cast(to_jsonb(result_row) as text)
            from tb_task_result result_row
            where result_row.task_config_id = :taskConfigId
              and result_row.id not in (:excludedIds)
            order by result_row.id asc
            """, nativeQuery = true)
    List<String> findFingerprintRowsByTaskConfigIdAndIdNotIn(
            @Param("taskConfigId") Long taskConfigId,
            @Param("excludedIds") Collection<Long> excludedIds);
}
