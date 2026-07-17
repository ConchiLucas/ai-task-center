package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TaskConfigServiceBatchTest {
    @Test
    void validationBatchLinksFormalResultsButCreatesCurrentValidationRun() {
        TaskConfigRepository taskRepository = mock(TaskConfigRepository.class);
        TaskResultRepository resultRepository = mock(TaskResultRepository.class);
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        TaskConfig task = task();
        TaskResult result = ttsResult();
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(resultRepository.findByTaskConfigIdAndRecordTypeAndStatusInOrderByIdAsc(
                eq(1L), eq(TaskRecordType.FORMAL), any(Collection.class)))
                .thenReturn(List.of(result));
        TaskConfigService service = service(taskRepository, resultRepository, jdbcTemplate);

        Map<String, Object> response = service.generateValidationRunBatch(1L, request());

        assertEquals(1, response.get("createdRunCount"));
        assertEquals(1, response.get("linkedResultCount"));
        assertEquals(TaskRecordType.VALIDATION_CURRENT, jdbcTemplate.insertArguments[17]);
        assertEquals(1, jdbcTemplate.linkRows.size());
        assertEquals(71L, jdbcTemplate.linkRows.get(0)[3]);
    }

    @Test
    void validationBatchSamplesFormalResults() {
        TaskConfigRepository taskRepository = mock(TaskConfigRepository.class);
        TaskResultRepository resultRepository = mock(TaskResultRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TaskConfig task = task();
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(resultRepository.findByTaskConfigIdAndRecordTypeAndStatusInOrderByIdAsc(
                eq(1L), eq(TaskRecordType.FORMAL), any(Collection.class)))
                .thenReturn(List.of());
        TaskConfigService service = service(taskRepository, resultRepository, jdbcTemplate);

        Map<String, Object> response = service.generateValidationRunBatch(1L, request());

        assertEquals(0, response.get("createdRunCount"));
        verify(resultRepository).findByTaskConfigIdAndRecordTypeAndStatusInOrderByIdAsc(
                eq(1L), eq(TaskRecordType.FORMAL), any(Collection.class));
    }

    @Test
    void formalBatchStillReadsFormalResults() {
        TaskConfigRepository taskRepository = mock(TaskConfigRepository.class);
        TaskResultRepository resultRepository = mock(TaskResultRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TaskConfig task = task();
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(resultRepository.findByTaskConfigIdAndRecordTypeAndStatusInOrderByIdAsc(
                eq(1L), eq(TaskRecordType.FORMAL), any(Collection.class)))
                .thenReturn(List.of());
        TaskConfigService service = service(taskRepository, resultRepository, jdbcTemplate);

        Map<String, Object> response = service.generateRunBatches(1L, request());

        assertEquals(0, response.get("createdRunCount"));
        verify(resultRepository).findByTaskConfigIdAndRecordTypeAndStatusInOrderByIdAsc(
                eq(1L), eq(TaskRecordType.FORMAL), any(Collection.class));
    }

    @Test
    void batchCopiesHandlerAndExecutionTargetSnapshot() {
        TaskConfigRepository taskRepository = mock(TaskConfigRepository.class);
        TaskResultRepository resultRepository = mock(TaskResultRepository.class);
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        TaskConfig task = task();
        task.setHandlerKey("word_clean_best_sentence_tts");
        task.setExecutorType("AI_PROVIDER");
        task.setExecutorId("xiaomi-mimo-tts");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(resultRepository.findByTaskConfigIdAndRecordTypeAndStatusInOrderByIdAsc(
                eq(1L), eq(TaskRecordType.FORMAL), any(Collection.class)))
                .thenReturn(List.of(ttsResult()));
        TaskConfigService service = service(taskRepository, resultRepository, jdbcTemplate);
        GenerateTaskRunBatchRequest request = request();
        request.setCliId(null);

        service.generateRunBatches(1L, request);

        assertTrue(jdbcTemplate.insertSql.contains("handler_key"));
        assertTrue(jdbcTemplate.insertSql.contains("executor_type"));
        assertTrue(jdbcTemplate.insertSql.contains("executor_id"));
        assertEquals("word_clean_best_sentence_tts", jdbcTemplate.insertArguments[5]);
        assertEquals("AI_PROVIDER", jdbcTemplate.insertArguments[6]);
        assertEquals("xiaomi-mimo-tts", jdbcTemplate.insertArguments[7]);
    }

    private static TaskConfigService service(
            TaskConfigRepository taskRepository,
            TaskResultRepository resultRepository,
            JdbcTemplate jdbcTemplate) {
        return new TaskConfigService(
                taskRepository,
                mock(ProjectConfigRepository.class),
                mock(ConnectionConfigRepository.class),
                resultRepository,
                mock(PythonWorkerClient.class),
                new TaskRunPromptBuilder(new ObjectMapper()),
                new TaskExecutionTargetResolver(),
                mock(AiConfigService.class),
                jdbcTemplate);
    }

    private static TaskConfig task() {
        TaskConfig task = new TaskConfig();
        task.setId(1L);
        task.setTaskName("生成 TTS 任务");
        task.setProjectId(1L);
        task.setCliId("codex");
        task.setDatabaseConfigId(1L);
        task.setSelectedTables("[\"public.word_clean_best_sentence\"]");
        return task;
    }

    private static GenerateTaskRunBatchRequest request() {
        GenerateTaskRunBatchRequest request = new GenerateTaskRunBatchRequest();
        request.setBatchSize(3);
        request.setCliId("codex");
        request.setTaskNamePrefix("生成 TTS 任务 - 验证");
        request.setIncludeFailed(false);
        return request;
    }

    private static TaskResult ttsResult() {
        TaskResult result = new TaskResult();
        result.setId(71L);
        result.setRecordType(TaskRecordType.FORMAL);
        result.setStatus("PENDING");
        result.setResultContent("""
                {
                  "taskType":"word_clean_best_sentence_tts",
                  "bestSentenceId":101,
                  "wordCleanId":7,
                  "word":"example",
                  "sourceTable":"public.word_clean_best_sentence",
                  "source":{"sourceSentenceId":501,"sentence":"This is an example."},
                  "ttsInput":{"text":"This is an example.","fileName":"best-101.wav"},
                  "writeBack":{"table":"public.word_clean_best_sentence","bestSentenceId":101}
                }
                """);
        return result;
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String insertSql;
        private Object[] insertArguments;
        private List<Object[]> linkRows = List.of();

        @Override
        public int update(String sql, Object... args) {
            return 0;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            insertSql = sql;
            insertArguments = args;
            return requiredType.cast(99L);
        }

        @Override
        public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
            linkRows = batchArgs;
            return new int[batchArgs.size()];
        }
    }
}
