package com.aitaskcenter.repository;

import com.aitaskcenter.model.ConnectionConfig;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionConfigRepository extends JpaRepository<ConnectionConfig, Long> {
    List<ConnectionConfig> findByConnectionGroupOrderByCreatedAtDesc(String connectionGroup);
}
