package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskOnboardingResetServiceTest {
    @Test
    void resetCleansValidationThenFormalChildrenAndRecordsOverwrite() throws Exception {
        TaskConfigRepository tasks = mock(TaskConfigRepository.class);
        TaskOnboardingCleanupService cleanup = mock(TaskOnboardingCleanupService.class);
        TaskOnboardingChildTableLock locks = mock(TaskOnboardingChildTableLock.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        TaskConfig task = new TaskConfig();
        task.setId(7L);
        task.setOnboardingStep(OnboardingStep.RESULT_VALIDATION.name());
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setResultValidationRunId("a".repeat(64));
        context.setResultValidationIds(List.of(11L));
        task.setOnboardingContext(mapper.writeValueAsString(context));
        when(tasks.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), anyLong()))
                .thenReturn(2L);

        TaskOnboardingResetService service = new TaskOnboardingResetService(
                tasks, cleanup, locks, jdbc, new TaskOnboardingContextCodec(mapper));
        TaskOnboardingContext reset = service.prepareSemanticReset(7L);

        InOrder order = inOrder(cleanup, locks, jdbc);
        order.verify(locks).lockForCleanup();
        order.verify(cleanup).deleteResultValidation(7L, "a".repeat(64));
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(anyString(), anyLong());
        assertTrue(reset.isOverwriteExistingFormalResults());
    }

    @Test
    void resetDiscoversAndExactCleansUnreportedResultValidation() throws Exception {
        TaskConfigRepository tasks = mock(TaskConfigRepository.class);
        TaskOnboardingCleanupService cleanup = mock(TaskOnboardingCleanupService.class);
        TaskOnboardingChildTableLock locks = mock(TaskOnboardingChildTableLock.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        TaskConfig task = new TaskConfig();
        task.setId(7L);
        task.setOnboardingStep(OnboardingStep.RESULT_VALIDATION.name());
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setResultValidationRunId("b".repeat(64));
        task.setOnboardingContext(mapper.writeValueAsString(context));
        when(tasks.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(jdbc.queryForList(
                org.mockito.ArgumentMatchers.contains("source_description"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("RESULT_VALIDATION:" + "b".repeat(64))))
                .thenReturn(List.of(21L));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), anyLong()))
                .thenReturn(0L);

        new TaskOnboardingResetService(
                tasks, cleanup, locks, jdbc, new TaskOnboardingContextCodec(mapper))
                .prepareSemanticReset(7L);

        verify(locks).lockForCleanup();
        verify(cleanup).deleteResultValidation(7L, "b".repeat(64));
        assertTrue(mapper.readValue(task.getOnboardingContext(), TaskOnboardingContext.class)
                .getResultValidationIds().contains(21L));
    }
}
