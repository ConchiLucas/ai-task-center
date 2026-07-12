package com.aitaskcenter.repository;

import com.aitaskcenter.model.TaskRun;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {
    List<TaskRun> findAllByOrderByCreatedAtDesc();

    List<TaskRun> findByTaskConfigIdAndReasonOrderByIdAsc(Long taskConfigId, String reason);

    Optional<TaskRun> findByIdAndTaskConfigIdAndReason(Long id, Long taskConfigId, String reason);

    // 方法：findByDispatchGroupIdIn
    List<TaskRun> findByDispatchGroupIdIn(Collection<String> dispatchGroupIds);

    @Query(value = """
            select cast(to_jsonb(run_row) as text)
            from tb_task_run run_row
            where run_row.task_config_id = :taskConfigId
            order by run_row.id asc
            """, nativeQuery = true)
    List<String> findFingerprintRowsByTaskConfigId(@Param("taskConfigId") Long taskConfigId);

    @Query(value = """
            select cast(to_jsonb(run_row) as text)
            from tb_task_run run_row
            where run_row.task_config_id = :taskConfigId
              and run_row.id <> :excludedId
            order by run_row.id asc
            """, nativeQuery = true)
    List<String> findFingerprintRowsByTaskConfigIdAndIdNot(
            @Param("taskConfigId") Long taskConfigId,
            @Param("excludedId") Long excludedId);
}
