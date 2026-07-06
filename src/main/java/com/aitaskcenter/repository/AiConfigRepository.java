package com.aitaskcenter.repository;

import com.aitaskcenter.model.AiConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConfigRepository extends JpaRepository<AiConfig, Long> {
    Optional<AiConfig> findByConfigKey(String configKey);
}
