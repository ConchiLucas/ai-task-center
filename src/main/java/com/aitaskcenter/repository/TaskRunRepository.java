package com.aitaskcenter.repository;

import com.aitaskcenter.model.TaskRun;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {
    List<TaskRun> findAllByOrderByCreatedAtDesc();

    // 方法：findByDispatchGroupIdIn
    List<TaskRun> findByDispatchGroupIdIn(Collection<String> dispatchGroupIds);

    List<TaskRun> findByTaskConfigIdAndRecordTypeOrderByIdDesc(Long taskConfigId, String recordType);

    long countByTaskConfigIdAndRecordType(Long taskConfigId, String recordType);
}
