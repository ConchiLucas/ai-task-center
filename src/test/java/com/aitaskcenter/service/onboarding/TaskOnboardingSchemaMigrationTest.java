package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import jakarta.persistence.Column;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskOnboardingSchemaMigrationTest {
    @Test
    void runsIdempotentBackfillBeforeHibernateSchemaUpdate() throws Exception {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertNotNull(properties);
        assertEquals("always", properties.getProperty("spring.sql.init.mode"));
        assertEquals("^^^", properties.getProperty("spring.sql.init.separator"));
        assertEquals("false", properties.getProperty("spring.jpa.defer-datasource-initialization"));

        String migration = new ClassPathResource("schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(migration.contains("to_regclass('tb_task_config')"));
        assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS onboarding_step"));
        assertTrue(migration.contains("UPDATE tb_task_config"));
        assertTrue(migration.contains("WHERE onboarding_step IS NULL"));
        assertTrue(migration.contains("ALTER COLUMN onboarding_context SET NOT NULL"));

        assertColumnDefault("onboardingStep", "varchar(40) default 'RESULT_CODE'");
        assertColumnDefault("onboardingStatus", "varchar(40) default 'ACTIVE'");
        assertColumnDefault("onboardingContext", "text default '{}'");
    }

    private void assertColumnDefault(String fieldName, String expected) throws NoSuchFieldException {
        Column column = TaskConfig.class.getDeclaredField(fieldName).getAnnotation(Column.class);
        assertNotNull(column);
        assertEquals(expected, column.columnDefinition());
    }
}
