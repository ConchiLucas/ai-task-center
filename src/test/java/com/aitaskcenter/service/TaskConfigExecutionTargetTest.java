package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.ExecutionTargetItem;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.ConnectionConfigRepository;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TaskConfigExecutionTargetTest {
    @Test
    void rejectsTtsHandlerUsingTextOnlyCli() {
        TaskConfigService service = service();
        TaskConfig input = ttsTask("CLI", "codex");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(input));

        assertEquals("调用通道「codex」不支持任务所需能力 AUDIO_TTS", error.getMessage());
    }

    @Test
    void acceptsTtsHandlerUsingMimoProvider() {
        TaskConfigRepository repository = mock(TaskConfigRepository.class);
        TaskConfigService service = service(repository);
        when(repository.save(any(TaskConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TaskConfig saved = service.create(ttsTask("AI_PROVIDER", "xiaomi-mimo-tts"));

        assertEquals("word_clean_best_sentence_tts", saved.getHandlerKey());
        assertEquals("AI_PROVIDER", saved.getExecutorType());
        assertEquals("xiaomi-mimo-tts", saved.getExecutorId());
        assertEquals("codex", saved.getOnboardingCliId());
    }

    @Test
    void rejectsUnknownHandlerKey() {
        TaskConfig task = ttsTask("CLI", "codex");
        task.setHandlerKey("unknown-handler");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service().create(task));

        assertEquals("任务处理器不支持: unknown-handler", error.getMessage());
    }

    private static TaskConfigService service() {
        return service(mock(TaskConfigRepository.class));
    }

    private static TaskConfigService service(TaskConfigRepository repository) {
        ProjectConfigRepository projectRepository = mock(ProjectConfigRepository.class);
        when(projectRepository.existsById(1L)).thenReturn(true);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        when(aiConfigService.getExecutionTargets()).thenReturn(List.of(
                new ExecutionTargetItem(
                        "CLI",
                        "codex",
                        "Codex CLI",
                        "local-cli",
                        List.of("TEXT_GENERATION", "CODE_EXECUTION"),
                        true),
                new ExecutionTargetItem(
                        "AI_PROVIDER",
                        "xiaomi-mimo-tts",
                        "小米 MiMo TTS",
                        "mimo-tts",
                        List.of("AUDIO_TTS"),
                        true)));
        return new TaskConfigService(
                repository,
                projectRepository,
                mock(ConnectionConfigRepository.class),
                mock(TaskResultRepository.class),
                mock(PythonWorkerClient.class),
                new TaskRunPromptBuilder(new ObjectMapper()),
                new TaskExecutionTargetResolver(),
                aiConfigService,
                mock(JdbcTemplate.class));
    }

    private static TaskConfig ttsTask(String executorType, String executorId) {
        TaskConfig task = new TaskConfig();
        task.setTaskName("生成 TTS 任务");
        task.setProjectId(1L);
        task.setCliId("codex");
        task.setOnboardingCliId("codex");
        task.setHandlerKey("word_clean_best_sentence_tts");
        task.setExecutorType(executorType);
        task.setExecutorId(executorId);
        return task;
    }
}
