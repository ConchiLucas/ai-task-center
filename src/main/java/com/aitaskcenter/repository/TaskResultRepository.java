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

    // 方法：findByTaskConfigIdAndStatusInOrderByIdAsc
    List<TaskResult> findByTaskConfigIdAndStatusInOrderByIdAsc(Long taskConfigId, Collection<String> statuses);

    // 方法：findIdsByTaskConfigIdAndStatusIn
    @Query("select item.id from TaskResult item where item.taskConfigId = :taskConfigId and item.status in :statuses order by item.id asc")
    List<Long> findIdsByTaskConfigIdAndStatusIn(
            @Param("taskConfigId") Long taskConfigId,
            @Param("statuses") Collection<String> statuses);
}
