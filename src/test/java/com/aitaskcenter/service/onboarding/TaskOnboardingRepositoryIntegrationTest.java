package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.model.TaskRunResult;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskOnboardingRepositoryIntegrationTest {
    private static final String DATABASE_URL = setting(
            "task.center.test.db.url", "TASK_CENTER_DB_URL", "jdbc:postgresql://localhost:5432/ai_task_center");
    private static final String DATABASE_USER = setting(
            "task.center.test.db.user", "TASK_CENTER_DB_USER", "conchi");
    private static final String DATABASE_PASSWORD = setting(
            "task.center.test.db.password", "TASK_CENTER_DB_PASSWORD", "conchi123456");
    private static final String SCHEMA = "task_onboarding_repo_"
            + UUID.randomUUID().toString().replace("-", "");

    static {
        try (Connection connection = DriverManager.getConnection(DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String separator = DATABASE_URL.contains("?") ? "&" : "?";
        registry.add("spring.datasource.url", () -> DATABASE_URL + separator + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> DATABASE_USER);
        registry.add("spring.datasource.password", () -> DATABASE_PASSWORD);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private TaskConfigRepository taskConfigRepository;
    @Autowired
    private TaskResultRepository taskResultRepository;
    @Autowired
    private TaskRunRepository taskRunRepository;
    @Autowired
    private TaskRunResultRepository taskRunResultRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TaskConfig task;

    @BeforeEach
    void createTask() {
        taskRunResultRepository.deleteAll();
        taskRunRepository.deleteAll();
        taskResultRepository.deleteAll();
        taskConfigRepository.deleteAll();
        task = new TaskConfig();
        task.setTaskName("Repository integration task");
        task.setProjectId(1L);
        task.setCliId("codex");
        task.setSelectedTables("[\"word\"]");
        task = taskConfigRepository.saveAndFlush(task);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void fingerprintProjectionsAreTaskScopedOrderedAndExcludeAllowedRows() {
        TaskResult first = result("first");
        first.setSourceDescription("RESULT_VALIDATION:run-1");
        TaskResult second = result("second");
        taskResultRepository.saveAllAndFlush(List.of(first, second));
        TaskRun run = run();
        run.setReason("BATCH_VALIDATION:run-2");
        taskRunRepository.saveAndFlush(run);
        TaskRunResult link = new TaskRunResult();
        link.setTaskRunId(run.getId());
        link.setTaskResultId(first.getId());
        taskRunResultRepository.saveAndFlush(link);

        List<String> resultRows = taskResultRepository.findFingerprintRowsByTaskConfigId(task.getId());
        List<String> protectedRows = taskResultRepository.findFingerprintRowsByTaskConfigIdAndIdNotIn(
                task.getId(), List.of(second.getId()));

        assertEquals(2, resultRows.size());
        assertTrue(resultRows.get(0).contains("\"result_name\": \"first\""));
        assertEquals(1, protectedRows.size());
        assertTrue(taskRunRepository.findFingerprintRowsByTaskConfigId(task.getId()).get(0)
                .contains("\"task_config_id\": " + task.getId()));
        assertEquals(1, taskRunResultRepository.findFingerprintRowsByTaskConfigId(task.getId()).size());
        assertEquals(List.of(first.getId()), taskResultRepository
                .findByIdInAndTaskConfigIdAndSourceDescriptionOrderByIdAsc(
                        List.of(first.getId(), second.getId()),
                        task.getId(),
                        "RESULT_VALIDATION:run-1")
                .stream()
                .map(TaskResult::getId)
                .toList());
        assertTrue(taskRunRepository.findByIdAndTaskConfigIdAndReason(
                run.getId(), task.getId(), "BATCH_VALIDATION:run-2").isPresent());
        assertEquals(List.of(first.getId()), taskResultRepository.findValidationRunResults(
                        List.of(first.getId()),
                        run.getId(),
                        task.getId(),
                        "BATCH_VALIDATION:run-2")
                .stream()
                .map(TaskResult::getId)
                .toList());
    }

    @Test
    void pessimisticWriteLookupBlocksConcurrentTokenMutation() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                taskConfigRepository.findByIdForUpdate(task.getId()).orElseThrow();
                firstLocked.countDown();
                await(releaseFirst);
            }));
            assertTrue(firstLocked.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> transaction.executeWithoutResult(status ->
                    taskConfigRepository.findByIdForUpdate(task.getId()).orElseThrow()));

            Thread.sleep(200);
            assertFalse(second.isDone());

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private TaskResult result(String name) {
        TaskResult result = new TaskResult();
        result.setTaskConfigId(task.getId());
        result.setProjectId(1L);
        result.setResultName(name);
        result.setResultContent("{\"value\":\"" + name + "\"}");
        return result;
    }

    private TaskRun run() {
        TaskRun run = new TaskRun();
        run.setTaskConfigId(task.getId());
        run.setTaskName("validation run");
        run.setProjectId(1L);
        run.setCliId("codex");
        return run;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for lock test latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static String setting(String systemProperty, String environmentVariable, String fallback) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentVariable);
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
