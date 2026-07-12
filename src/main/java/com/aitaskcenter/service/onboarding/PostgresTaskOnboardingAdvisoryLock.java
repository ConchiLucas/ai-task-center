package com.aitaskcenter.service.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresTaskOnboardingAdvisoryLock implements TaskOnboardingAdvisoryLock {
    private final JdbcTemplate jdbcTemplate;

    public PostgresTaskOnboardingAdvisoryLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void lockTask(Long taskConfigId) {
        if (taskConfigId == null) {
            throw new IllegalArgumentException("Missing task configuration ID");
        }
        jdbcTemplate.query("""
                select pg_advisory_xact_lock(
                    hashtextextended('task-onboarding:' || cast(? as text), 0))
                """, resultSet -> null, taskConfigId);
    }
}
