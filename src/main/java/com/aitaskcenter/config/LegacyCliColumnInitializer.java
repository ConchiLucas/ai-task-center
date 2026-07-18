package com.aitaskcenter.config;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LegacyCliColumnInitializer implements ApplicationRunner {
    private static final List<String> STATEMENTS = List.of(
            "alter table if exists tb_task_config alter column cli_id drop not null",
            "alter table if exists tb_task_result alter column cli_id drop not null",
            "alter table if exists tb_task_run alter column cli_id drop not null",
            "alter table if exists tb_task_execution_log alter column cli_id drop not null");

    private final JdbcTemplate jdbcTemplate;

    public LegacyCliColumnInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        STATEMENTS.forEach(jdbcTemplate::execute);
    }
}
