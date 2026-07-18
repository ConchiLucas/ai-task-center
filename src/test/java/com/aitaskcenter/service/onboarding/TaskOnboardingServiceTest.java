package com.aitaskcenter.service.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.TaskOnboardingReportRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import com.aitaskcenter.service.TaskConfigService;
import com.aitaskcenter.service.AiConfigService;
import com.aitaskcenter.service.PythonWorkerClient;
import com.aitaskcenter.dto.ExecutionTargetItem;
import com.aitaskcenter.dto.TaskHandlerDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskOnboardingServiceTest {
    @Test
    void codeReadyOnlyAdvancesToManualValidation() {
        TaskConfigRepository taskRepository = mock(TaskConfigRepository.class);
        TaskResultRepository resultRepository = mock(TaskResultRepository.class);
        TaskRunRepository runRepository = mock(TaskRunRepository.class);
        TaskRunResultRepository linkRepository = mock(TaskRunResultRepository.class);
        TaskConfigService taskConfigService = mock(TaskConfigService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        TaskOnboardingPromptBuilder promptBuilder = new TaskOnboardingPromptBuilder(objectMapper);

        TaskConfig task = new TaskConfig();
        task.setId(12L);
        task.setTaskName("测试任务");
        task.setProjectId(1L);
        task.setExecutorType("CLI");
        task.setExecutorId("codex");
        task.setOnboardingStep(OnboardingStep.RESULT_CODE.name());
        task.setOnboardingContext("{\"resultCodeToken\":\"token-1\"}");
        when(taskRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(TaskConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(resultRepository.findByTaskConfigIdAndRecordTypeOrderByIdAsc(any(), any())).thenReturn(List.of());
        when(runRepository.findByTaskConfigIdAndRecordTypeOrderByIdDesc(any(), any())).thenReturn(List.of());

        AiConfigService aiConfigService = mock(AiConfigService.class);
        when(aiConfigService.getExecutionTargets()).thenReturn(List.of(
                new ExecutionTargetItem(
                        "CLI", "codex", "Codex CLI", "local-cli", List.of("TEXT_GENERATION"), true)));
        PythonWorkerClient workerClient = mock(PythonWorkerClient.class);
        when(workerClient.getTaskHandler("task_config_12")).thenReturn(
                new TaskHandlerDescriptor(
                        "task_config_12", "TEXT_GENERATION", true, true, false, true));
        TaskOnboardingService service = new TaskOnboardingService(
                taskRepository, resultRepository, runRepository, linkRepository,
                taskConfigService, promptBuilder, objectMapper, aiConfigService, workerClient);
        TaskOnboardingReportRequest request = new TaskOnboardingReportRequest();
        request.setStage("result");
        request.setStatus("CODE_READY");
        request.setToken("token-1");

        var response = service.report(12L, request);

        assertEquals(OnboardingStep.RESULT_VALIDATION.name(), task.getOnboardingStep());
        assertEquals(OnboardingStep.RESULT_VALIDATION.name(), response.getCurrentStep());
        assertEquals(List.of("GENERATE_RESULT_VALIDATION"), response.getAllowedActions());
    }
}
