package com.aitaskcenter.service.onboarding;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskOnboardingSchemaMigrationTest {
    private static final String DATABASE_URL = setting(
            "task.center.test.db.url",
            "TASK_CENTER_DB_URL",
            "jdbc:postgresql://localhost:5432/ai_task_center");
    private static final String DATABASE_USER = setting(
            "task.center.test.db.user",
            "TASK_CENTER_DB_USER",
            "conchi");
    private static final String DATABASE_PASSWORD = setting(
            "task.center.test.db.password",
            "TASK_CENTER_DB_PASSWORD",
            "conchi123456");

    private String schemaName;
    private DataSource schemaDataSource;

    @BeforeEach
    void createIsolatedSchema() throws SQLException {
        schemaName = "task_onboarding_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schemaName);
        }
        String separator = DATABASE_URL.contains("?") ? "&" : "?";
        schemaDataSource = new DriverManagerDataSource(
                DATABASE_URL + separator + "currentSchema=" + schemaName,
                DATABASE_USER,
                DATABASE_PASSWORD);
    }

    @AfterEach
    void dropIsolatedSchema() throws SQLException {
        if (schemaName == null) {
            return;
        }
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
            try (PreparedStatement check = connection.prepareStatement("SELECT to_regnamespace(?)")) {
                check.setString(1, schemaName);
                try (ResultSet resultSet = check.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertNull(resultSet.getObject(1));
                }
            }
        }
    }

    @Test
    void migratesLegacyRowsAndEnforcesDefaultsAndNotNullConstraints() throws Exception {
        execute("CREATE TABLE tb_task_config (id bigint PRIMARY KEY)");
        execute("INSERT INTO tb_task_config (id) VALUES (1)");

        runMigration(schemaDataSource);

        assertEquals(
                List.of("RESULT_CODE", "ACTIVE", "{}"),
                onboardingValues(1));
        assertColumnNotNull("onboarding_step");
        assertColumnNotNull("onboarding_status");
        assertColumnNotNull("onboarding_context");

        execute("INSERT INTO tb_task_config (id) VALUES (2)");
        assertEquals(
                List.of("RESULT_CODE", "ACTIVE", "{}"),
                onboardingValues(2));
    }

    @Test
    void secondInitializationPerformsNoTableAlteration() throws Exception {
        execute("CREATE TABLE tb_task_config (id bigint PRIMARY KEY)");
        runMigration(schemaDataSource);

        try (Connection blocker = schemaDataSource.getConnection();
                Connection runner = schemaDataSource.getConnection();
                Statement blockerStatement = blocker.createStatement();
                Statement runnerStatement = runner.createStatement()) {
            blocker.setAutoCommit(false);
            try (ResultSet ignored = blockerStatement.executeQuery("SELECT * FROM tb_task_config")) {
                runnerStatement.execute("SET lock_timeout = '100ms'");
                runMigration(new SingleConnectionDataSource(runner, true));
            } finally {
                blocker.rollback();
            }
        }
    }

    @Test
    void initializationSucceedsWhenTaskConfigTableIsAbsent() throws Exception {
        runMigration(schemaDataSource);

        try (Connection connection = schemaDataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT to_regclass('tb_task_config')")) {
            assertTrue(resultSet.next());
            assertNull(resultSet.getObject(1));
        }
    }

    @Test
    void createsCompositeOnboardingLookupIndexesIdempotently() throws Exception {
        execute("CREATE TABLE tb_task_config (id bigint PRIMARY KEY)");
        execute("""
                CREATE TABLE tb_task_result (
                    id bigint PRIMARY KEY,
                    task_config_id bigint,
                    source_description varchar(1000)
                )
                """);
        execute("""
                CREATE TABLE tb_task_run (
                    id bigint PRIMARY KEY,
                    task_config_id bigint,
                    reason varchar(1000)
                )
                """);

        runMigration(schemaDataSource);
        runMigration(schemaDataSource);

        assertIndexDefinition(
                "idx_task_result_onboarding_marker",
                "task_config_id, source_description, id");
        assertIndexDefinition(
                "idx_task_run_onboarding_marker",
                "task_config_id, reason, id");
    }

    @Test
    void createsUniqueRunResultIndexAndRejectsLegacyDuplicatesClearly() throws Exception {
        execute("CREATE TABLE tb_task_config (id bigint PRIMARY KEY)");
        execute("CREATE TABLE tb_task_run_result (id bigint PRIMARY KEY, task_run_id bigint, task_result_id bigint)");
        runMigration(schemaDataSource);
        runMigration(schemaDataSource);
        assertIndexDefinition("uk_task_run_result_run_result", "task_run_id, task_result_id");

        execute("DROP INDEX uk_task_run_result_run_result");
        execute("INSERT INTO tb_task_run_result VALUES (1, 10, 20), (2, 10, 20)");
        RuntimeException error = assertThrows(RuntimeException.class, () -> runMigration(schemaDataSource));
        assertTrue(rootMessage(error).contains("duplicate task-run/result links"));
    }

    private void runMigration(DataSource dataSource) {
        DatabaseInitializationSettings settings = new DatabaseInitializationSettings();
        settings.setMode(DatabaseInitializationMode.ALWAYS);
        settings.setSchemaLocations(List.of("classpath:schema.sql"));
        settings.setSeparator("^^^");
        assertTrue(new DataSourceScriptDatabaseInitializer(dataSource, settings).initializeDatabase());
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = schemaDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<String> onboardingValues(long id) throws SQLException {
        String sql = "SELECT onboarding_step, onboarding_status, onboarding_context "
                + "FROM tb_task_config WHERE id = ?";
        try (Connection connection = schemaDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return List.of(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3));
            }
        }
    }

    private void assertColumnNotNull(String columnName) throws SQLException {
        String sql = "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'tb_task_config' AND column_name = ?";
        try (Connection connection = schemaDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("NO", resultSet.getString(1));
            }
        }
    }

    private void assertIndexDefinition(String indexName, String expectedColumns) throws SQLException {
        String sql = "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = ?";
        try (Connection connection = schemaDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaName);
            statement.setString(2, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getString(1).contains("(" + expectedColumns + ")"));
            }
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    private static String setting(String systemProperty, String environmentVariable, String fallback) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentVariable);
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
