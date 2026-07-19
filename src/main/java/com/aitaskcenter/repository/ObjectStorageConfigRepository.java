package com.aitaskcenter.repository;

import com.aitaskcenter.model.ObjectStorageConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObjectStorageConfigRepository extends JpaRepository<ObjectStorageConfig, Long> {
    List<ObjectStorageConfig> findAllByOrderByCreatedAtDesc();

    Optional<ObjectStorageConfig> findByIsDefaultTrue();
}
