package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.model.TaskRunResult;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskOnboardingCleanupServiceTest {
    private static final Long TASK_ID = 7L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TaskConfigRepository taskConfigRepository;
    private TaskResultRepository taskResultRepository;
    private TaskRunRepository taskRunRepository;
    private TaskRunResultRepository taskRunResultRepository;
    private NamedParameterJdbcTemplate jdbcTemplate;
    private TaskOnboardingChildTableLock childTableLock;
    private TaskOnboardingCleanupService service;

    @BeforeEach
    void setUp() {
        taskConfigRepository = mock(TaskConfigRepository.class);
        taskResultRepository = mock(TaskResultRepository.class);
        taskRunRepository = mock(TaskRunRepository.class);
        taskRunResultRepository = mock(TaskRunResultRepository.class);
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        childTableLock = mock(TaskOnboardingChildTableLock.class);
        service = new TaskOnboardingCleanupService(
                taskConfigRepository,
                taskResultRepository,
                taskRunRepository,
                taskRunResultRepository,
                jdbcTemplate,
                childTableLock,
                new TaskOnboardingContextCodec(OBJECT_MAPPER),
                OBJECT_MAPPER);
    }

    @Test
    void resultCleanupUsesTaskAndExactMarkerAndRefusesLinkedRows() {
        TaskConfig task = resultTask("result-run-1", List.of(101L));
        TaskResult result = result(101L, "RESULT_VALIDATION:result-run-1");
        when(taskConfigRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(taskResultRepository.findByTaskConfigIdAndSourceDescriptionOrderByIdAsc(
                TASK_ID, "RESULT_VALIDATION:result-run-1"))
                .thenReturn(List.of(result));
        when(taskRunResultRepository.countByTaskResultIdIn(List.of(101L))).thenReturn(1L);

        assertThrows(IllegalStateException.class,
                () -> service.deleteResultValidation(TASK_ID, "result-run-1"));

        InOrder lockOrder = inOrder(childTableLock, taskResultRepository);
        lockOrder.verify(childTableLock).lockForCleanup();
        lockOrder.verify(taskResultRepository).findByTaskConfigIdAndSourceDescriptionOrderByIdAsc(
                TASK_ID, "RESULT_VALIDATION:result-run-1");
        verify(taskResultRepository).findByTaskConfigIdAndSourceDescriptionOrderByIdAsc(
                TASK_ID, "RESULT_VALIDATION:result-run-1");
        verify(jdbcTemplate, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void batchCleanupDeletesLogsThenLinksThenRunWithoutDeletingResults() {
        TaskConfig task = batchTask("batch-run-1", 301L, List.of(201L, 202L));
        TaskRun run = run(301L, "BATCH_VALIDATION:batch-run-1");
        List<TaskRunResult> links = List.of(link(401L, 301L, 201L), link(402L, 301L, 202L));
        when(taskConfigRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(taskRunRepository.findByIdAndTaskConfigIdAndReason(
                301L, TASK_ID, "BATCH_VALIDATION:batch-run-1"))
                .thenReturn(Optional.of(run), Optional.empty());
        when(taskRunResultRepository.findByTaskRunIdOrderByIdAsc(301L))
                .thenReturn(links, List.of());
        when(taskRunResultRepository.countLinkedResultsForRunAndTask(
                301L, TASK_ID, List.of(201L, 202L))).thenReturn(2L);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(0, 2, 1);
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);

        assertEquals(1, service.deleteBatchValidation(TASK_ID, 301L, "batch-run-1"));

        verify(childTableLock).lockForCleanup();
        InOrder deletionOrder = inOrder(jdbcTemplate);
        deletionOrder.verify(jdbcTemplate).update(
                argThat(sql -> normalized(sql).contains("delete from tb_task_execution_log")),
                any(MapSqlParameterSource.class));
        deletionOrder.verify(jdbcTemplate).update(
                argThat(sql -> normalized(sql).contains("delete from tb_task_run_result")),
                any(MapSqlParameterSource.class));
        deletionOrder.verify(jdbcTemplate).update(
                argThat(sql -> normalized(sql).startsWith("delete from tb_task_run ")),
                any(MapSqlParameterSource.class));
        verify(jdbcTemplate, never()).update(
                argThat(sql -> normalized(sql).contains("delete from tb_task_result")),
                any(MapSqlParameterSource.class));
        verify(taskResultRepository, never()).delete(any(TaskResult.class));
    }

    private static TaskConfig resultTask(String validationRunId, List<Long> ids) {
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setResultValidationRunId(validationRunId);
        context.setResultValidationIds(ids);
        TaskConfig task = task(context);
        task.setOnboardingStep(OnboardingStep.RESULT_GENERATION.name());
        return task;
    }

    private static TaskConfig batchTask(String marker, Long runId, List<Long> resultIds) {
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setBatchValidationMarker(marker);
        context.setBatchValidationTaskRunId(runId);
        context.setBatchValidationResultIds(resultIds);
        TaskConfig task = task(context);
        task.setOnboardingStep(OnboardingStep.BATCH_GENERATION.name());
        return task;
    }

    private static TaskConfig task(TaskOnboardingContext context) {
        TaskConfig task = new TaskConfig();
        task.setId(TASK_ID);
        try {
            task.setOnboardingContext(OBJECT_MAPPER.writeValueAsString(context));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return task;
    }

    private static TaskResult result(Long id, String marker) {
        TaskResult result = new TaskResult();
        result.setId(id);
        result.setTaskConfigId(TASK_ID);
        result.setSourceDescription(marker);
        result.setResultContent(metadata(marker));
        return result;
    }

    private static TaskRun run(Long id, String marker) {
        TaskRun run = new TaskRun();
        run.setId(id);
        run.setTaskConfigId(TASK_ID);
        run.setReason(marker);
        run.setAiPromptJson(metadata(marker));
        run.setStatus("PENDING");
        return run;
    }

    private static TaskRunResult link(Long id, Long runId, Long resultId) {
        TaskRunResult link = new TaskRunResult();
        link.setId(id);
        link.setTaskRunId(runId);
        link.setTaskResultId(resultId);
        return link;
    }

    private static String metadata(String marker) {
        return "{\"_meta\":{\"validationRunId\":\"" + marker + "\"}}";
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
