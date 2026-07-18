package com.aitaskcenter.service.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.ExecutionTargetItem;
import com.aitaskcenter.dto.TaskHandlerDescriptor;
import com.aitaskcenter.dto.TaskOnboardingReportRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import com.aitaskcenter.service.AiConfigService;
import com.aitaskcenter.service.PythonWorkerClient;
import com.aitaskcenter.service.TaskConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskOnboardingHandlerReadinessTest {
    @Test
    void resultCodeReadyRequiresRegisteredCompatibleHandler() {
        Fixture fixture = fixture(resultCodeTask(), textTarget());
        when(fixture.workerClient.getTaskHandler("task_config_42")).thenReturn(
                new TaskHandlerDescriptor(
                        "task_config_42", "TEXT_GENERATION", true, true, false, true));

        var response = fixture.service.report(42L, codeReady("result", "token-1"));

        assertEquals("task_config_42", fixture.task.getHandlerKey());
        assertEquals(OnboardingStep.RESULT_VALIDATION.name(), fixture.task.getOnboardingStep());
        assertEquals("task_config_42", response.getTask().handlerKey());
    }

    @Test
    void capabilityMismatchDoesNotAdvanceOrAssignHandler() {
        Fixture fixture = fixture(resultCodeTask(), textTarget());
        when(fixture.workerClient.getTaskHandler("task_config_42")).thenReturn(
                new TaskHandlerDescriptor(
                        "task_config_42", "AUDIO_TTS", true, true, true, true));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.report(42L, codeReady("result", "token-1")));

        assertEquals("模型调用通道不支持任务处理器所需能力 AUDIO_TTS", error.getMessage());
        assertEquals(OnboardingStep.RESULT_CODE.name(), fixture.task.getOnboardingStep());
        assertNull(fixture.task.getHandlerKey());
    }

    @Test
    void resultCodeReadyRequiresBothResultPhases() {
        Fixture fixture = fixture(resultCodeTask(), textTarget());
        when(fixture.workerClient.getTaskHandler("task_config_42")).thenReturn(
                new TaskHandlerDescriptor(
                        "task_config_42", "TEXT_GENERATION", true, false, true, true));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.report(42L, codeReady("result", "token-1")));

        assertEquals("任务处理器尚未实现结果生成和单条验证", error.getMessage());
    }

    @Test
    void batchCodeReadyRequiresBatchBuildAndExecution() {
        TaskConfig task = resultCodeTask();
        task.setOnboardingStep(OnboardingStep.BATCH_CODE.name());
        task.setOnboardingContext("{\"batchCodeToken\":\"batch-token\"}");
        task.setHandlerKey("task_config_42");
        Fixture fixture = fixture(task, textTarget());
        when(fixture.workerClient.getTaskHandler("task_config_42")).thenReturn(
                new TaskHandlerDescriptor(
                        "task_config_42", "TEXT_GENERATION", true, true, false, true));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.report(42L, codeReady("batch", "batch-token")));

        assertEquals("任务处理器尚未实现批次构建和批次执行", error.getMessage());
        assertEquals(OnboardingStep.BATCH_CODE.name(), fixture.task.getOnboardingStep());
    }

    private static Fixture fixture(TaskConfig task, ExecutionTargetItem target) {
        TaskConfigRepository taskRepository = mock(TaskConfigRepository.class);
        TaskResultRepository resultRepository = mock(TaskResultRepository.class);
        TaskRunRepository runRepository = mock(TaskRunRepository.class);
        TaskRunResultRepository linkRepository = mock(TaskRunResultRepository.class);
        PythonWorkerClient workerClient = mock(PythonWorkerClient.class);
        AiConfigService aiConfigService = mock(AiConfigService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(taskRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(TaskConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(resultRepository.findByTaskConfigIdAndRecordTypeOrderByIdAsc(any(), any())).thenReturn(List.of());
        when(runRepository.findByTaskConfigIdAndRecordTypeOrderByIdDesc(any(), any())).thenReturn(List.of());
        when(aiConfigService.getExecutionTargets()).thenReturn(List.of(target));
        TaskOnboardingService service = new TaskOnboardingService(
                taskRepository,
                resultRepository,
                runRepository,
                linkRepository,
                mock(TaskConfigService.class),
                new TaskOnboardingPromptBuilder(objectMapper),
                objectMapper,
                aiConfigService,
                workerClient);
        return new Fixture(service, task, workerClient);
    }

    private static TaskConfig resultCodeTask() {
        TaskConfig task = new TaskConfig();
        task.setId(42L);
        task.setTaskName("测试任务");
        task.setProjectId(1L);
        task.setExecutorType("CLI");
        task.setExecutorId("codex");
        task.setOnboardingStep(OnboardingStep.RESULT_CODE.name());
        task.setOnboardingContext("{\"resultCodeToken\":\"token-1\"}");
        return task;
    }

    private static ExecutionTargetItem textTarget() {
        return new ExecutionTargetItem(
                "CLI", "codex", "Codex CLI", "local-cli", List.of("TEXT_GENERATION"), true);
    }

    private static TaskOnboardingReportRequest codeReady(String stage, String token) {
        TaskOnboardingReportRequest request = new TaskOnboardingReportRequest();
        request.setStage(stage);
        request.setStatus("CODE_READY");
        request.setToken(token);
        return request;
    }

    private record Fixture(
            TaskOnboardingService service,
            TaskConfig task,
            PythonWorkerClient workerClient) {}
}
