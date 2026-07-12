package com.aitaskcenter.service.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresTaskOnboardingChildTableLock implements TaskOnboardingChildTableLock {
    private final JdbcTemplate jdbcTemplate;

    public PostgresTaskOnboardingChildTableLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * PostgreSQL SHARE locks conflict with the ROW EXCLUSIVE locks taken by child
     * INSERT, UPDATE, and DELETE statements. Every callback uses this fixed order
     * so concurrent validation transactions cannot deadlock by reversing tables.
     */
    @Override
    public void lockForCallbackValidation() {
        lockAllChildrenInGlobalOrder();
    }

    @Override
    public void lockForCleanup() {
        lockAllChildrenInGlobalOrder();
    }

    /**
     * Global onboarding child-table lock order: result, run, run-result,
     * execution-log. Cleanup and callback transactions must never reverse it.
     */
    private void lockAllChildrenInGlobalOrder() {
        jdbcTemplate.execute("LOCK TABLE tb_task_result IN SHARE MODE");
        jdbcTemplate.execute("LOCK TABLE tb_task_run IN SHARE MODE");
        jdbcTemplate.execute("LOCK TABLE tb_task_run_result IN SHARE MODE");
        jdbcTemplate.execute("LOCK TABLE tb_task_execution_log IN SHARE MODE");
    }
}
