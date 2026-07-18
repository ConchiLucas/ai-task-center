package com.aitaskcenter.service.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.ExecutionTargetItem;
import com.aitaskcenter.dto.SelectExecutionTargetRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import com.aitaskcenter.service.AiConfigService;
import com.aitaskcenter.service.TaskConfigService;
import com.aitaskcenter.service.PythonWorkerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskOnboardingTargetSelectionTest {
    @Test
    void selectsOneEnabledRuntimeTargetAndAdvancesToResultCode() throws Exception {
        Fixture fixture = fixture(task(OnboardingStep.TARGET_SELECTION, "{}"));

        var response = fixture.service.selectExecutionTarget(
                12L, new SelectExecutionTargetRequest("AI_PROVIDER", "xiaomi-mimo-tts"));

        assertEquals(OnboardingStep.RESULT_CODE.name(), response.getCurrentStep());
        assertEquals("AI_PROVIDER", response.getTask().executorType());
        assertEquals("xiaomi-mimo-tts", response.getTask().executorId());
        assertNull(response.getTask().handlerKey());
        Map<String, Object> context = fixture.objectMapper.readValue(
                fixture.task.getOnboardingContext(), Map.class);
        assertEquals(true, context.containsKey("resultCodeToken"));
    }

    @Test
    void rejectsDisabledOrUnknownRuntimeTarget() {
        Fixture fixture = fixture(task(OnboardingStep.TARGET_SELECTION, "{}"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.selectExecutionTarget(
                        12L, new SelectExecutionTargetRequest("CLI", "disabled-cli")));

        assertEquals("模型调用通道不存在或未启用", error.getMessage());
    }

    @Test
    void changingTargetResetsRegisteredCodeAndRotatesToken() throws Exception {
        TaskConfig task = task(
                OnboardingStep.RESULT_VALIDATION,
                "{\"resultCodeToken\":\"old-token\",\"resultCodeReadyAt\":\"2026-07-18T10:00:00+08:00\",\"formalResultCount\":9}");
        task.setExecutorType("CLI");
        task.setExecutorId("codex");
        task.setHandlerKey("task_config_12");
        Fixture fixture = fixture(task);

        fixture.service.selectExecutionTarget(
                12L, new SelectExecutionTargetRequest("AI_PROVIDER", "xiaomi-mimo-tts"));

        assertEquals(OnboardingStep.RESULT_CODE.name(), fixture.task.getOnboardingStep());
        assertEquals("AI_PROVIDER", fixture.task.getExecutorType());
        assertEquals("xiaomi-mimo-tts", fixture.task.getExecutorId());
        assertNull(fixture.task.getHandlerKey());
        Map<String, Object> context = fixture.objectMapper.readValue(
                fixture.task.getOnboardingContext(), Map.class);
        assertNotEquals("old-token", context.get("resultCodeToken"));
        assertEquals(false, context.containsKey("resultCodeReadyAt"));
        assertEquals(false, context.containsKey("formalResultCount"));
    }

    @Test
    void selectingSameTargetAtResultCodeKeepsCurrentToken() throws Exception {
        TaskConfig task = task(OnboardingStep.RESULT_CODE, "{\"resultCodeToken\":\"same-token\"}");
        task.setExecutorType("CLI");
        task.setExecutorId("codex");
        Fixture fixture = fixture(task);

        fixture.service.selectExecutionTarget(
                12L, new SelectExecutionTargetRequest("CLI", "codex"));

        Map<String, Object> context = fixture.objectMapper.readValue(
                fixture.task.getOnboardingContext(), Map.class);
        assertEquals("same-token", context.get("resultCodeToken"));
    }

    private static Fixture fixture(TaskConfig task) {
        TaskConfigRepository taskRepository = mock(TaskConfigRepository.class);
        TaskResultRepository resultRepository = mock(TaskResultRepository.class);
        TaskRunRepository runRepository = mock(TaskRunRepository.class);
        TaskRunResultRepository linkRepository = mock(TaskRunResultRepository.class);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(taskRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(TaskConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(resultRepository.findByTaskConfigIdAndRecordTypeOrderByIdAsc(any(), any())).thenReturn(List.of());
        when(runRepository.findByTaskConfigIdAndRecordTypeOrderByIdDesc(any(), any())).thenReturn(List.of());
        when(aiConfigService.getExecutionTargets()).thenReturn(List.of(
                new ExecutionTargetItem("CLI", "codex", "Codex CLI", "local-cli", List.of("TEXT_GENERATION"), true),
                new ExecutionTargetItem("CLI", "disabled-cli", "Disabled", "local-cli", List.of("TEXT_GENERATION"), false),
                new ExecutionTargetItem("AI_PROVIDER", "xiaomi-mimo-tts", "小米 MiMo TTS", "mimo-tts", List.of("AUDIO_TTS"), true)));
        TaskOnboardingService service = new TaskOnboardingService(
                taskRepository,
                resultRepository,
                runRepository,
                linkRepository,
                mock(TaskConfigService.class),
                new TaskOnboardingPromptBuilder(objectMapper),
                objectMapper,
                aiConfigService,
                mock(PythonWorkerClient.class));
        return new Fixture(service, task, objectMapper);
    }

    private static TaskConfig task(OnboardingStep step, String context) {
        TaskConfig task = new TaskConfig();
        task.setId(12L);
        task.setTaskName("测试任务");
        task.setProjectId(1L);
        task.setOnboardingStep(step.name());
        task.setOnboardingContext(context);
        return task;
    }

    private record Fixture(TaskOnboardingService service, TaskConfig task, ObjectMapper objectMapper) {}
}
