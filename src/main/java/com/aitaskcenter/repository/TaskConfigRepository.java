package com.aitaskcenter.repository;

import com.aitaskcenter.model.TaskConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface TaskConfigRepository extends JpaRepository<TaskConfig, Long> {
    List<TaskConfig> findAllByOrderByCreatedAtDesc();

    List<TaskConfig> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from TaskConfig item where item.id = :id")
    Optional<TaskConfig> findByIdForUpdate(@Param("id") Long id);
}
