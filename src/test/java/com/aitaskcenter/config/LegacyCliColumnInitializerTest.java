package com.aitaskcenter.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LegacyCliColumnInitializerTest {
    @Test
    void relaxesRetainedLegacyCliColumnsWithoutDeletingData() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LegacyCliColumnInitializer initializer = new LegacyCliColumnInitializer(jdbcTemplate);

        initializer.run(null);

        verify(jdbcTemplate).execute("alter table if exists tb_task_config alter column cli_id drop not null");
        verify(jdbcTemplate).execute("alter table if exists tb_task_result alter column cli_id drop not null");
        verify(jdbcTemplate).execute("alter table if exists tb_task_run alter column cli_id drop not null");
        verify(jdbcTemplate).execute("alter table if exists tb_task_execution_log alter column cli_id drop not null");
    }
}
