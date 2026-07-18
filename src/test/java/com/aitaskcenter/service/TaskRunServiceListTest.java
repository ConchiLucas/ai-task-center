package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.CreateTaskRunRequest;
import com.aitaskcenter.dto.StartTaskRunRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskExecutionLog;
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
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class TaskRunServiceListTest {
    @Test
    void createdRunCopiesHandlerAndExecutionTargetSnapshot() {
        TaskRunRepository repository = mock(TaskRunRepository.class);
        TaskConfigRepository taskConfigRepository = mock(TaskConfigRepository.class);
        TaskConfig config = new TaskConfig();
        config.setId(1L);
        config.setTaskName("生成 TTS 任务");
        config.setProjectId(1L);
        config.setHandlerKey("word_clean_best_sentence_tts");
        config.setExecutorType("AI_PROVIDER");
        config.setExecutorId("xiaomi-mimo-tts");
        when(taskConfigRepository.findById(1L)).thenReturn(Optional.of(config));
        when(repository.save(any(TaskRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TaskRunService service = new TaskRunService(
                repository,
                taskConfigRepository,
                mock(ProjectConfigRepository.class),
                mock(TaskResultRepository.class),
                mock(TaskRunResultRepository.class),
                mock(TaskExecutionLogRepository.class),
                mock(PythonWorkerClient.class),
                new TaskExecutionTargetResolver());
        CreateTaskRunRequest request = new CreateTaskRunRequest();
        request.setTaskConfigId(1L);

        TaskRun created = service.create(request);

        assertEquals("word_clean_best_sentence_tts", created.getHandlerKey());
        assertEquals("AI_PROVIDER", created.getExecutorType());
        assertEquals("xiaomi-mimo-tts", created.getExecutorId());
    }

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
                new TaskExecutionTargetResolver());

        List<TaskRun> results = service.list(
                null, null, 1L, null, null, null, TaskRecordType.VALIDATION_CURRENT);

        assertEquals(List.of(validationForTask), results);
    }

    @Test
    void filtersRunsByStoredProviderTarget() {
        TaskRunRepository repository = mock(TaskRunRepository.class);
        TaskRun legacyTts = taskRun(1L, TaskRecordType.FORMAL);
        legacyTts.setExecutorType("AI_PROVIDER");
        legacyTts.setExecutorId("xiaomi-mimo-tts");
        TaskRun scoring = taskRun(2L, TaskRecordType.FORMAL);
        scoring.setSelectedTables("[\"public.word_clean_sentence\"]");
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(legacyTts, scoring));
        TaskRunService service = service(
                repository,
                mock(TaskRunResultRepository.class),
                mock(TaskExecutionLogRepository.class));

        List<TaskRun> results = service.list(
                null,
                null,
                null,
                "AI_PROVIDER",
                "xiaomi-mimo-tts",
                null,
                TaskRecordType.FORMAL);

        assertEquals(List.of(legacyTts), results);
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
        request.setExecutionMode("thread");
        request.setWorkerCount(2);

        Map<String, Object> response = service.startExecution(request);

        assertEquals(true, response.get("accepted"));
        assertEquals(1, response.get("queuedCount"));
        assertEquals("QUEUED", run.getStatus());
        verify(repository).saveAll(List.of(run));
    }

    @Test
    void queuesTtsRunWithoutCliSelectionAndKeepsExecutionTargetSnapshot() {
        TaskRunRepository repository = mock(TaskRunRepository.class);
        TaskRunResultRepository linkRepository = mock(TaskRunResultRepository.class);
        TaskExecutionLogRepository executionRepository = mock(TaskExecutionLogRepository.class);
        TaskRun run = taskRun(1L, TaskRecordType.FORMAL);
        run.setId(6056L);
        run.setHandlerKey("word_clean_best_sentence_tts");
        run.setExecutorType("AI_PROVIDER");
        run.setExecutorId("xiaomi-mimo-tts");
        when(repository.findAllById(List.of(6056L))).thenReturn(List.of(run));
        when(linkRepository.findByTaskRunIdInOrderByTaskRunIdAscIdAsc(List.of(6056L))).thenReturn(List.of());
        when(executionRepository.countByTaskRunId(6056L)).thenReturn(0L);
        TaskRunService service = service(repository, linkRepository, executionRepository);
        StartTaskRunRequest request = new StartTaskRunRequest();
        request.setTaskRunIds(List.of(6056L));
        request.setExecutionMode("thread");
        request.setWorkerCount(2);

        Map<String, Object> response = service.startExecution(request);

        assertEquals(true, response.get("accepted"));
        assertEquals("AI_PROVIDER", run.getExecutorType());
        assertEquals("xiaomi-mimo-tts", run.getExecutorId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskExecutionLog>> executionCaptor = ArgumentCaptor.forClass(List.class);
        verify(executionRepository).saveAll(executionCaptor.capture());
        TaskExecutionLog execution = executionCaptor.getValue().get(0);
        assertEquals("word_clean_best_sentence_tts", execution.getHandlerKey());
        assertEquals("AI_PROVIDER", execution.getExecutorType());
        assertEquals("xiaomi-mimo-tts", execution.getExecutorId());
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
                new TaskExecutionTargetResolver());
    }

    private static TaskRun taskRun(Long taskConfigId, String recordType) {
        TaskRun run = new TaskRun();
        run.setTaskName("测试批次");
        run.setTaskConfigId(taskConfigId);
        run.setProjectId(1L);
        run.setStatus("PENDING");
        run.setRecordType(recordType);
        run.setHandlerKey("task_config_" + taskConfigId);
        run.setExecutorType("CLI");
        run.setExecutorId("codex");
        return run;
    }
}
