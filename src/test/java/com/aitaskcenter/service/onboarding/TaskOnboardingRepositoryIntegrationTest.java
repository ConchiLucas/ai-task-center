package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.dto.TaskOnboardingReportRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.model.TaskRunResult;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        TaskOnboardingService.class,
        TaskOnboardingContextCodec.class,
        TaskOnboardingSnapshotService.class,
        TaskOnboardingCallbackValidator.class,
        TaskOnboardingResponseAssembler.class,
        TaskOnboardingPromptBuilder.class,
        PostgresTaskOnboardingChildTableLock.class,
        TaskOnboardingRepositoryIntegrationTest.JsonTestConfiguration.class
})
class TaskOnboardingRepositoryIntegrationTest {
    private static final String ARTIFACT_HASH = "a".repeat(64);
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
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TaskOnboardingChildTableLock childTableLock;
    @Autowired
    private TaskOnboardingService onboardingService;
    @Autowired
    private ObjectMapper objectMapper;

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
    void linkFingerprintIncludesLinksTouchingTaskOnEitherSide() {
        TaskResult thisTaskResult = result("this-task-result");
        taskResultRepository.saveAndFlush(thisTaskResult);

        TaskConfig otherTask = new TaskConfig();
        otherTask.setTaskName("Other task");
        otherTask.setProjectId(1L);
        otherTask.setCliId("codex");
        otherTask.setSelectedTables("[\"word\"]");
        otherTask = taskConfigRepository.saveAndFlush(otherTask);
        TaskRun otherTaskRun = run();
        otherTaskRun.setTaskConfigId(otherTask.getId());
        taskRunRepository.saveAndFlush(otherTaskRun);

        TaskRunResult crossTaskLink = new TaskRunResult();
        crossTaskLink.setTaskRunId(otherTaskRun.getId());
        crossTaskLink.setTaskResultId(thisTaskResult.getId());
        taskRunResultRepository.saveAndFlush(crossTaskLink);

        TaskResult otherTaskResult = result("other-task-result");
        otherTaskResult.setTaskConfigId(otherTask.getId());
        taskResultRepository.saveAndFlush(otherTaskResult);
        TaskRun thisTaskRun = taskRunRepository.saveAndFlush(run());
        TaskRunResult reverseCrossTaskLink = new TaskRunResult();
        reverseCrossTaskLink.setTaskRunId(thisTaskRun.getId());
        reverseCrossTaskLink.setTaskResultId(otherTaskResult.getId());
        taskRunResultRepository.saveAndFlush(reverseCrossTaskLink);

        List<String> rows = taskRunResultRepository.findFingerprintRowsByTaskConfigId(task.getId());

        assertEquals(2, rows.size());
        assertTrue(rows.get(0).contains("\"id\": " + crossTaskLink.getId()));
        assertTrue(rows.get(1).contains("\"id\": " + reverseCrossTaskLink.getId()));
    }

    @Test
    void cleanHibernateSchemaCreatesOnboardingMarkerIndexes() {
        List<String> indexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = current_schema()", String.class);

        assertTrue(indexes.contains("idx_task_result_onboarding_marker"));
        assertTrue(indexes.contains("idx_task_run_onboarding_marker"));
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

    @Test
    void childTableShareLocksBlockWritesUntilValidationTransactionEnds() throws Exception {
        TaskResult result = taskResultRepository.saveAndFlush(result("locked-result"));
        TaskRun run = taskRunRepository.saveAndFlush(run());
        TaskRunResult link = new TaskRunResult();
        link.setTaskRunId(run.getId());
        link.setTaskResultId(result.getId());
        link = taskRunResultRepository.saveAndFlush(link);
        Long resultId = result.getId();
        Long runId = run.getId();
        Long linkId = link.getId();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch tablesLocked = new CountDownLatch(1);
        CountDownLatch releaseLocks = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Future<?> holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                childTableLock.lockForCallbackValidation();
                tablesLocked.countDown();
                await(releaseLocks);
                status.setRollbackOnly();
            }));
            assertTrue(tablesLocked.await(5, TimeUnit.SECONDS));

            Future<?> resultWrite = executor.submit(() -> blockingWrite(
                    "update tb_task_result set status = status where id = ?", resultId));
            Future<?> runWrite = executor.submit(() -> blockingWrite(
                    "update tb_task_run set status = status where id = ?", runId));
            Future<?> linkWrite = executor.submit(() -> blockingWrite(
                    "update tb_task_run_result set task_result_id = task_result_id where id = ?", linkId));

            Thread.sleep(250);
            assertFalse(resultWrite.isDone());
            assertFalse(runWrite.isDone());
            assertFalse(linkWrite.isDone());

            releaseLocks.countDown();
            holder.get(5, TimeUnit.SECONDS);
            resultWrite.get(5, TimeUnit.SECONDS);
            runWrite.get(5, TimeUnit.SECONDS);
            linkWrite.get(5, TimeUnit.SECONDS);
        } finally {
            releaseLocks.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void realResultCallbackAcceptsLegalValidationInsert() throws Exception {
        taskResultRepository.saveAndFlush(result("protected-result"));
        onboardingService.get(task.getId());
        TaskOnboardingContext issued = onboardingContext();
        TaskResult validation = validationResult(issued);
        taskResultRepository.saveAndFlush(validation);

        onboardingService.report(task.getId(), resultReport(issued, List.of(validation.getId())));

        TaskConfig persisted = taskConfigRepository.findById(task.getId()).orElseThrow();
        TaskOnboardingContext accepted = objectMapper.readValue(
                persisted.getOnboardingContext(), TaskOnboardingContext.class);
        assertEquals(OnboardingStep.RESULT_VALIDATION.name(), persisted.getOnboardingStep());
        assertEquals(List.of(validation.getId()), accepted.getResultValidationIds());
        assertEquals("", accepted.getResultReportToken());
    }

    @Test
    void realResultCallbackRejectsExtraUnmarkedChildAndPreservesToken() throws Exception {
        onboardingService.get(task.getId());
        TaskOnboardingContext issued = onboardingContext();
        TaskResult validation = validationResult(issued);
        taskResultRepository.saveAndFlush(validation);
        taskResultRepository.saveAndFlush(result("forbidden-extra"));

        assertThrows(IllegalArgumentException.class, () -> onboardingService.report(
                task.getId(), resultReport(issued, List.of(validation.getId()))));

        assertCallbackIdentityUnchanged(issued);
    }

    @Test
    void realResultCallbackRejectsModifiedProtectedChildAndPreservesToken() throws Exception {
        TaskResult protectedResult = taskResultRepository.saveAndFlush(result("protected-result"));
        onboardingService.get(task.getId());
        TaskOnboardingContext issued = onboardingContext();
        protectedResult.setResultContent("{\"forbidden\":true}");
        taskResultRepository.saveAndFlush(protectedResult);
        TaskResult validation = validationResult(issued);
        taskResultRepository.saveAndFlush(validation);

        assertThrows(IllegalArgumentException.class, () -> onboardingService.report(
                task.getId(), resultReport(issued, List.of(validation.getId()))));

        assertCallbackIdentityUnchanged(issued);
    }

    private void blockingWrite(String sql, Long id) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            jdbcTemplate.execute("set local lock_timeout = '5s'");
            assertEquals(1, jdbcTemplate.update(sql, id));
        });
    }

    private TaskOnboardingContext onboardingContext() throws Exception {
        return objectMapper.readValue(
                taskConfigRepository.findById(task.getId()).orElseThrow().getOnboardingContext(),
                TaskOnboardingContext.class);
    }

    private TaskResult validationResult(TaskOnboardingContext issued) {
        String marker = "RESULT_VALIDATION:" + issued.getResultValidationRunId();
        TaskResult validation = result("validation-result");
        validation.setSourceDescription(marker);
        validation.setResultContent("{\"_meta\":{\"validationRunId\":\"" + marker + "\"}}");
        return validation;
    }

    private TaskOnboardingReportRequest resultReport(TaskOnboardingContext issued, List<Long> ids) {
        TaskOnboardingReportRequest request = new TaskOnboardingReportRequest();
        request.setStage("result");
        request.setToken(issued.getResultReportToken());
        request.setArtifact("src/result-generator.py");
        request.setArtifactHash(ARTIFACT_HASH);
        request.setEntityIds(ids);
        return request;
    }

    private void assertCallbackIdentityUnchanged(TaskOnboardingContext issued) throws Exception {
        TaskConfig persisted = taskConfigRepository.findById(task.getId()).orElseThrow();
        TaskOnboardingContext unchanged = objectMapper.readValue(
                persisted.getOnboardingContext(), TaskOnboardingContext.class);
        assertEquals(OnboardingStep.RESULT_CODE.name(), persisted.getOnboardingStep());
        assertEquals(issued.getResultValidationRunId(), unchanged.getResultValidationRunId());
        assertEquals(issued.getResultReportToken(), unchanged.getResultReportToken());
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

    @TestConfiguration
    static class JsonTestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
