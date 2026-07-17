package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.StartTaskRunRequest;
import com.aitaskcenter.model.TaskRecordType;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskExecutionLogRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskRunServiceListTest {
    @Test
    void listsCurrentValidationRunsByTaskConfig() {
        TaskRunRepository repository = mock(TaskRunRepository.class);
        TaskRun formal = taskRun(1L, TaskRecordType.FORMAL);
        TaskRun validationForTask = taskRun(1L, TaskRecordType.VALIDATION_CURRENT);
        TaskRun validationForOtherTask = taskRun(2L, TaskRecordType.VALIDATION_CURRENT);
        when(repository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(formal, validationForTask, validationForOtherTask));

        TaskRunService service = new TaskRunService(
                repository,
                mock(TaskConfigRepository.class),
                mock(ProjectConfigRepository.class),
                mock(TaskResultRepository.class),
                mock(TaskRunResultRepository.class),
                mock(TaskExecutionLogRepository.class),
                mock(PythonWorkerClient.class),
                mock(TaskRunPromptBuilder.class));

        List<TaskRun> results = service.list(
                null, null, 1L, null, null, TaskRecordType.VALIDATION_CURRENT);

        assertEquals(List.of(validationForTask), results);
    }

    @Test
    void queuesCurrentValidationRunForIsolatedExecution() {
        TaskRunRepository repository = mock(TaskRunRepository.class);
        TaskRunResultRepository linkRepository = mock(TaskRunResultRepository.class);
        TaskExecutionLogRepository executionRepository = mock(TaskExecutionLogRepository.class);
        TaskRun run = taskRun(1L, TaskRecordType.VALIDATION_CURRENT);
        run.setId(1573L);
        when(repository.findAllById(List.of(1573L))).thenReturn(List.of(run));
        when(linkRepository.findByTaskRunIdInOrderByTaskRunIdAscIdAsc(List.of(1573L))).thenReturn(List.of());
        when(executionRepository.countByTaskRunId(1573L)).thenReturn(0L);
        TaskRunService service = service(repository, linkRepository, executionRepository);
        StartTaskRunRequest request = new StartTaskRunRequest();
        request.setTaskRunIds(List.of(1573L));
        request.setCliId("codex");
        request.setExecutionMode("thread");
        request.setWorkerCount(2);

        Map<String, Object> response = service.startExecution(request);

        assertEquals(true, response.get("accepted"));
        assertEquals(1, response.get("queuedCount"));
        assertEquals("QUEUED", run.getStatus());
        verify(repository).saveAll(List.of(run));
    }

    @Test
    void currentValidationRunCanBeDeletedButHistoryCannot() {
        TaskRunRepository repository = mock(TaskRunRepository.class);
        TaskRun current = taskRun(1L, TaskRecordType.VALIDATION_CURRENT);
        current.setId(1573L);
        TaskRun history = taskRun(1L, TaskRecordType.VALIDATION_HISTORY);
        history.setId(1572L);
        when(repository.findById(1573L)).thenReturn(Optional.of(current));
        when(repository.findById(1572L)).thenReturn(Optional.of(history));
        TaskRunService service = service(
                repository,
                mock(TaskRunResultRepository.class),
                mock(TaskExecutionLogRepository.class));

        service.delete(1573L);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.delete(1572L));

        verify(repository).delete(current);
        verify(repository, never()).delete(history);
        assertEquals("历史验证批次仅供查看，不能执行、重试、取消或删除", error.getMessage());
    }

    @Test
    void historyValidationRunCannotEnterQueue() {
        TaskRunRepository repository = mock(TaskRunRepository.class);
        TaskRun history = taskRun(1L, TaskRecordType.VALIDATION_HISTORY);
        history.setId(1572L);
        when(repository.findAllById(List.of(1572L))).thenReturn(List.of(history));
        TaskRunService service = service(
                repository,
                mock(TaskRunResultRepository.class),
                mock(TaskExecutionLogRepository.class));
        StartTaskRunRequest request = new StartTaskRunRequest();
        request.setTaskRunIds(List.of(1572L));
        request.setCliId("codex");
        request.setExecutionMode("thread");
        request.setWorkerCount(1);

        assertThrows(IllegalArgumentException.class, () -> service.startExecution(request));
    }

    private static TaskRunService service(
            TaskRunRepository repository,
            TaskRunResultRepository linkRepository,
            TaskExecutionLogRepository executionRepository) {
        return new TaskRunService(
                repository,
                mock(TaskConfigRepository.class),
                mock(ProjectConfigRepository.class),
                mock(TaskResultRepository.class),
                linkRepository,
                executionRepository,
                mock(PythonWorkerClient.class),
                mock(TaskRunPromptBuilder.class));
    }

    private static TaskRun taskRun(Long taskConfigId, String recordType) {
        TaskRun run = new TaskRun();
        run.setTaskName("测试批次");
        run.setTaskConfigId(taskConfigId);
        run.setProjectId(1L);
        run.setCliId("codex");
        run.setStatus("PENDING");
        run.setRecordType(recordType);
        return run;
    }
}
