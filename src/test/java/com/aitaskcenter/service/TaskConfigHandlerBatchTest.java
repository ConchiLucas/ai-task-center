package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.GenerateTaskRunBatchRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskRecordType;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.repository.ConnectionConfigRepository;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TaskConfigHandlerBatchTest {
    @Test
    void generatedBatchUsesHandlerPromptAndExactTargetSnapshot() {
        TaskConfigRepository taskRepository = mock(TaskConfigRepository.class);
        TaskResultRepository resultRepository = mock(TaskResultRepository.class);
        PythonWorkerClient workerClient = mock(PythonWorkerClient.class);
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        TaskConfig task = new TaskConfig();
        task.setId(42L);
        task.setTaskName("任务 42");
        task.setProjectId(1L);
        task.setDatabaseConfigId(2L);
        task.setSelectedTables("[\"public.source\"]");
        task.setHandlerKey("task_config_42");
        task.setExecutorType("CLI");
        task.setExecutorId("codex");
        TaskResult result = new TaskResult();
        result.setId(101L);
        result.setStatus("PENDING");
        result.setRecordType(TaskRecordType.FORMAL);
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(resultRepository.findByTaskConfigIdAndRecordTypeAndStatusInOrderByIdAsc(
                eq(42L), eq(TaskRecordType.FORMAL), any(Collection.class)))
                .thenReturn(List.of(result));
        when(workerClient.buildBatchPrompt(
                "task_config_42", 42L, "任务 42 - 批次 1", List.of(101L)))
                .thenReturn("{\"_meta\":{\"handlerKey\":\"task_config_42\"}}");
        TaskConfigService service = new TaskConfigService(
                taskRepository,
                mock(ProjectConfigRepository.class),
                mock(ConnectionConfigRepository.class),
                resultRepository,
                workerClient,
                new TaskRunPromptBuilder(new ObjectMapper()),
                new TaskExecutionTargetResolver(),
                mock(AiConfigService.class),
                jdbcTemplate);
        GenerateTaskRunBatchRequest request = new GenerateTaskRunBatchRequest();
        request.setBatchSize(10);
        request.setIncludeFailed(false);

        service.generateRunBatches(42L, request);

        verify(workerClient).buildBatchPrompt(
                "task_config_42", 42L, "任务 42 - 批次 1", List.of(101L));
        assertEquals("task_config_42", jdbcTemplate.insertArguments[5]);
        assertEquals("CLI", jdbcTemplate.insertArguments[6]);
        assertEquals("codex", jdbcTemplate.insertArguments[7]);
        assertEquals(null, jdbcTemplate.insertArguments[8]);
        assertEquals("{\"_meta\":{\"handlerKey\":\"task_config_42\"}}", jdbcTemplate.insertArguments[15]);
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private Object[] insertArguments;

        @Override
        public int update(String sql, Object... args) {
            return 0;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            insertArguments = args;
            return requiredType.cast(99L);
        }

        @Override
        public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
            return new int[batchArgs.size()];
        }
    }
}
