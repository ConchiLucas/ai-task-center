package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.dto.TaskOnboardingReportRequest;
import com.aitaskcenter.dto.TaskOnboardingResponse;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void batchResponseOrdersValidationRunResultsByResultIdRegardlessOfLinkInsertionOrder() throws Exception {
        TaskResult lowerId = taskResultRepository.saveAndFlush(result("lower-id"));
        TaskResult higherId = taskResultRepository.saveAndFlush(result("higher-id"));
        TaskRun validationRun = run();
        validationRun.setReason("BATCH_VALIDATION:ordered-run");
        validationRun.setAiPromptJson(validationMetadata("BATCH_VALIDATION:ordered-run"));
        validationRun = taskRunRepository.saveAndFlush(validationRun);
        taskRunResultRepository.saveAndFlush(link(validationRun.getId(), higherId.getId()));
        taskRunResultRepository.saveAndFlush(link(validationRun.getId(), lowerId.getId()));

        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setBatchValidationMarker("ordered-run");
        context.setBatchValidationTaskRunId(validationRun.getId());
        context.setBatchValidationResultIds(List.of(lowerId.getId(), higherId.getId()));
        task.setOnboardingStep(OnboardingStep.BATCH_VALIDATION.name());
        task.setOnboardingContext(objectMapper.writeValueAsString(context));
        taskConfigRepository.saveAndFlush(task);

        TaskOnboardingResponse response = onboardingService.get(task.getId());

        assertEquals(List.of(lowerId.getId(), higherId.getId()), response.getValidationRunResults()
                .stream()
                .map(result -> result.id())
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
    void reportTransactionBlocksRealChildInsertsAndUpdatesUntilCommit() throws Exception {
        TaskResult protectedResult = taskResultRepository.saveAndFlush(result("locked-result"));
        TaskRun protectedRun = taskRunRepository.saveAndFlush(run());
        TaskRunResult protectedLink = taskRunResultRepository.saveAndFlush(
                link(protectedRun.getId(), protectedResult.getId()));
        onboardingService.get(task.getId());
        TaskOnboardingContext issued = onboardingContext();
        TaskResult validation = taskResultRepository.saveAndFlush(validationResult(issued));

        long advisoryKey = Integer.toUnsignedLong(SCHEMA.hashCode());
        installTaskConfigCommitGate(advisoryKey);
        ExecutorService executor = Executors.newFixedThreadPool(7);
        CountDownLatch writersReady = new CountDownLatch(6);
        CountDownLatch startWriters = new CountDownLatch(1);
        List<WriteAttempt> writes = List.of(
                new WriteAttempt("onboarding_writer_result_insert", """
                        /* onboarding_writer_result_insert */
                        insert into tb_task_result
                            (created_at, updated_at, result_name, project_id, status, task_config_id, result_content)
                        values (now(), now(), 'concurrent result', 1, 'PENDING', ?, '{}')
                        """, task.getId()),
                new WriteAttempt("onboarding_writer_result_update", """
                        /* onboarding_writer_result_update */
                        update tb_task_result set summary = 'concurrent update' where id = ?
                        """, protectedResult.getId()),
                new WriteAttempt("onboarding_writer_run_insert", """
                        /* onboarding_writer_run_insert */
                        insert into tb_task_run
                            (created_at, updated_at, task_name, task_config_id, project_id, cli_id, status)
                        values (now(), now(), 'concurrent run', ?, 1, 'codex', 'PENDING')
                        """, task.getId()),
                new WriteAttempt("onboarding_writer_run_update", """
                        /* onboarding_writer_run_update */
                        update tb_task_run set reason = 'concurrent update' where id = ?
                        """, protectedRun.getId()),
                new WriteAttempt("onboarding_writer_link_insert", """
                        /* onboarding_writer_link_insert */
                        insert into tb_task_run_result
                            (created_at, updated_at, task_run_id, task_result_id, status)
                        values (now(), now(), ?, ?, 'PENDING')
                        """, protectedRun.getId(), protectedResult.getId()),
                new WriteAttempt("onboarding_writer_link_update", """
                        /* onboarding_writer_link_update */
                        update tb_task_run_result set status = 'RUNNING' where id = ?
                        """, protectedLink.getId()));

        try (Connection gate = directConnection("onboarding_callback_gate");
                Statement gateStatement = gate.createStatement()) {
            gateStatement.execute("select pg_advisory_lock(" + advisoryKey + ")");
            Future<TaskOnboardingResponse> callback = executor.submit(() -> onboardingService.report(
                    task.getId(), resultReport(issued, List.of(validation.getId()))));
            awaitActivity("update tb_task_config", Set.of("Lock"));

            List<Future<Integer>> writerFutures = writes.stream()
                    .map(write -> executor.submit(() -> executeWrite(write, writersReady, startWriters)))
                    .toList();
            assertTrue(writersReady.await(5, TimeUnit.SECONDS));
            startWriters.countDown();
            awaitBlockedWriters(writes.stream()
                    .map(WriteAttempt::applicationName)
                    .collect(Collectors.toSet()));
            writerFutures.forEach(future -> assertFalse(future.isDone()));

            gateStatement.execute("select pg_advisory_unlock(" + advisoryKey + ")");
            callback.get(5, TimeUnit.SECONDS);
            for (Future<Integer> writer : writerFutures) {
                assertEquals(1, writer.get(5, TimeUnit.SECONDS));
            }
        } finally {
            startWriters.countDown();
            jdbcTemplate.execute("drop trigger if exists onboarding_callback_commit_gate on tb_task_config");
            jdbcTemplate.execute("drop function if exists onboarding_callback_commit_gate()");
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

    @Test
    void realBatchCallbackAcceptsLegalRunAndLinks() throws Exception {
        BatchFixture fixture = issueBatchCallback(false);

        TaskOnboardingResponse response = onboardingService.report(
                task.getId(), batchReport(fixture));

        TaskConfig persisted = taskConfigRepository.findById(task.getId()).orElseThrow();
        TaskOnboardingContext accepted = objectMapper.readValue(
                persisted.getOnboardingContext(), TaskOnboardingContext.class);
        assertEquals(OnboardingStep.BATCH_VALIDATION.name(), persisted.getOnboardingStep());
        assertEquals(fixture.validationRun().getId(), accepted.getBatchValidationTaskRunId());
        assertEquals(fixture.resultIds(), accepted.getBatchValidationResultIds());
        assertEquals("", accepted.getBatchReportToken());
        assertEquals(fixture.resultIds(), response.getValidationRunResults().stream()
                .map(result -> result.id())
                .toList());
    }

    @Test
    void realBatchCallbackRejectsExtraResultAndPreservesToken() throws Exception {
        BatchFixture fixture = issueBatchCallback(false);
        taskResultRepository.saveAndFlush(result("forbidden-extra-result"));

        assertRejectedBatchCallback(fixture);
    }

    @Test
    void realBatchCallbackRejectsModifiedResultAndPreservesToken() throws Exception {
        BatchFixture fixture = issueBatchCallback(false);
        fixture.results().get(0).setResultContent("{\"forbidden\":true}");
        taskResultRepository.saveAndFlush(fixture.results().get(0));

        assertRejectedBatchCallback(fixture);
    }

    @Test
    void realBatchCallbackRejectsExtraRunAndPreservesToken() throws Exception {
        BatchFixture fixture = issueBatchCallback(false);
        taskRunRepository.saveAndFlush(run());

        assertRejectedBatchCallback(fixture);
    }

    @Test
    void realBatchCallbackRejectsModifiedRunAndPreservesToken() throws Exception {
        BatchFixture fixture = issueBatchCallback(true);
        fixture.baselineRun().setReason("forbidden-run-update");
        taskRunRepository.saveAndFlush(fixture.baselineRun());

        assertRejectedBatchCallback(fixture);
    }

    @Test
    void realBatchCallbackRejectsExtraLinkAndPreservesToken() throws Exception {
        BatchFixture fixture = issueBatchCallback(true);
        taskRunResultRepository.saveAndFlush(
                link(fixture.baselineRun().getId(), fixture.results().get(1).getId()));

        assertRejectedBatchCallback(fixture);
    }

    @Test
    void realBatchCallbackRejectsModifiedLinkAndPreservesToken() throws Exception {
        BatchFixture fixture = issueBatchCallback(true);
        fixture.baselineLink().setStatus("RUNNING");
        taskRunResultRepository.saveAndFlush(fixture.baselineLink());

        assertRejectedBatchCallback(fixture);
    }

    private void installTaskConfigCommitGate(long advisoryKey) {
        jdbcTemplate.execute("""
                create or replace function onboarding_callback_commit_gate() returns trigger
                language plpgsql as $$
                begin
                    perform pg_advisory_lock(%d);
                    perform pg_advisory_unlock(%d);
                    return new;
                end
                $$
                """.formatted(advisoryKey, advisoryKey));
        jdbcTemplate.execute("""
                create trigger onboarding_callback_commit_gate
                before update on tb_task_config
                for each row execute function onboarding_callback_commit_gate()
                """);
    }

    private int executeWrite(
            WriteAttempt write, CountDownLatch writersReady, CountDownLatch startWriters) throws Exception {
        try (Connection connection = directConnection(write.applicationName());
                PreparedStatement statement = connection.prepareStatement(write.sql())) {
            try (Statement timeout = connection.createStatement()) {
                timeout.execute("set lock_timeout = '5s'");
            }
            for (int index = 0; index < write.parameters().length; index++) {
                statement.setObject(index + 1, write.parameters()[index]);
            }
            writersReady.countDown();
            await(startWriters);
            return statement.executeUpdate();
        }
    }

    private void awaitBlockedWriters(Set<String> expectedApplications) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Map<String, String> states = Map.of();
        while (System.nanoTime() < deadline) {
            Map<String, String> currentStates = jdbcTemplate.query("""
                    select application_name, coalesce(wait_event_type, '')
                    from pg_stat_activity
                    where application_name like 'onboarding_writer_%%'
                    """, resultSet -> {
                HashMap<String, String> values = new HashMap<>();
                while (resultSet.next()) {
                    values.put(resultSet.getString(1), resultSet.getString(2));
                }
                return values;
            });
            states = currentStates;
            if (currentStates.keySet().containsAll(expectedApplications)
                    && expectedApplications.stream().allMatch(name -> "Lock".equals(currentStates.get(name)))) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Writers did not all reach blocked SQL: " + states);
    }

    private void awaitActivity(String queryFragment, Set<String> expectedWaitTypes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            List<String> waits = jdbcTemplate.queryForList("""
                    select coalesce(wait_event_type, '')
                    from pg_stat_activity
                    where datname = current_database() and query like ?
                    """, String.class, "%" + queryFragment + "%");
            if (waits.stream().anyMatch(expectedWaitTypes::contains)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Callback did not reach the controlled TaskConfig update");
    }

    private Connection directConnection(String applicationName) throws SQLException {
        String separator = DATABASE_URL.contains("?") ? "&" : "?";
        return DriverManager.getConnection(
                DATABASE_URL + separator + "currentSchema=" + SCHEMA + "&ApplicationName=" + applicationName,
                DATABASE_USER,
                DATABASE_PASSWORD);
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

    private BatchFixture issueBatchCallback(boolean withBaselineLink) throws Exception {
        List<TaskResult> formalResults = taskResultRepository.saveAllAndFlush(List.of(
                result("formal-one"), result("formal-two")));
        TaskRun baselineRun = null;
        TaskRunResult baselineLink = null;
        if (withBaselineLink) {
            baselineRun = taskRunRepository.saveAndFlush(run());
            baselineLink = taskRunResultRepository.saveAndFlush(
                    link(baselineRun.getId(), formalResults.get(0).getId()));
        }
        task.setOnboardingStep(OnboardingStep.BATCH_CODE.name());
        task.setOnboardingContext("{}");
        taskConfigRepository.saveAndFlush(task);
        onboardingService.get(task.getId());
        TaskOnboardingContext issued = onboardingContext();
        String marker = "BATCH_VALIDATION:" + issued.getBatchValidationMarker();
        TaskRun validationRun = run();
        validationRun.setReason(marker);
        validationRun.setAiPromptJson(validationMetadata(marker));
        validationRun = taskRunRepository.saveAndFlush(validationRun);
        taskRunResultRepository.saveAndFlush(
                link(validationRun.getId(), formalResults.get(1).getId()));
        taskRunResultRepository.saveAndFlush(
                link(validationRun.getId(), formalResults.get(0).getId()));
        List<Long> resultIds = formalResults.stream().map(TaskResult::getId).sorted().toList();
        return new BatchFixture(issued, formalResults, resultIds, validationRun, baselineRun, baselineLink);
    }

    private TaskOnboardingReportRequest batchReport(BatchFixture fixture) {
        TaskOnboardingReportRequest request = new TaskOnboardingReportRequest();
        request.setStage("batch");
        request.setToken(fixture.issued().getBatchReportToken());
        request.setArtifact("src/batch-generator.py");
        request.setArtifactHash(ARTIFACT_HASH);
        ArrayList<Long> entityIds = new ArrayList<>();
        entityIds.add(fixture.validationRun().getId());
        entityIds.addAll(fixture.resultIds());
        request.setEntityIds(entityIds);
        return request;
    }

    private void assertRejectedBatchCallback(BatchFixture fixture) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> onboardingService.report(
                task.getId(), batchReport(fixture)));
        TaskConfig persisted = taskConfigRepository.findById(task.getId()).orElseThrow();
        TaskOnboardingContext unchanged = objectMapper.readValue(
                persisted.getOnboardingContext(), TaskOnboardingContext.class);
        assertEquals(OnboardingStep.BATCH_CODE.name(), persisted.getOnboardingStep());
        assertEquals(fixture.issued().getBatchValidationMarker(), unchanged.getBatchValidationMarker());
        assertEquals(fixture.issued().getBatchReportToken(), unchanged.getBatchReportToken());
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

    private TaskRunResult link(Long runId, Long resultId) {
        TaskRunResult link = new TaskRunResult();
        link.setTaskRunId(runId);
        link.setTaskResultId(resultId);
        return link;
    }

    private String validationMetadata(String marker) {
        return "{\"_meta\":{\"validationRunId\":\"" + marker + "\"}}";
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

    private record WriteAttempt(String applicationName, String sql, Object... parameters) {
    }

    private record BatchFixture(
            TaskOnboardingContext issued,
            List<TaskResult> results,
            List<Long> resultIds,
            TaskRun validationRun,
            TaskRun baselineRun,
            TaskRunResult baselineLink) {
    }

    @TestConfiguration
    static class JsonTestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
