package com.aitaskcenter.controller;

import com.aitaskcenter.dto.GenerateTaskRunBatchRequest;
import com.aitaskcenter.dto.TaskOnboardingResponse;
import com.aitaskcenter.service.TaskConfigService;
import com.aitaskcenter.service.onboarding.TaskOnboardingService;
import com.aitaskcenter.service.onboarding.TaskOnboardingStateException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskConfigControllerTest {
    private static final long TASK_CONFIG_ID = 42L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TaskConfigService taskConfigService;
    private TaskOnboardingService onboardingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        taskConfigService = mock(TaskConfigService.class);
        onboardingService = mock(TaskOnboardingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new TaskConfigController(taskConfigService, onboardingService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void legacyResultGenerationDelegatesToOnboardingGate() throws Exception {
        when(onboardingService.generateResults(TASK_CONFIG_ID)).thenReturn(response("BATCH_CODE"));

        mockMvc.perform(post("/api/task/{id}/generate-results", TASK_CONFIG_ID)
                        .param("overwrite", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.currentStep").value("BATCH_CODE"));

        verify(onboardingService).generateResults(TASK_CONFIG_ID);
        verifyNoInteractions(taskConfigService);
    }

    @Test
    void legacyBatchGenerationDelegatesToOnboardingGate() throws Exception {
        GenerateTaskRunBatchRequest request = batchRequest();
        when(onboardingService.generateBatches(eq(TASK_CONFIG_ID), any(GenerateTaskRunBatchRequest.class)))
                .thenReturn(response("READY"));

        mockMvc.perform(post("/api/task/{id}/generate-run-batches", TASK_CONFIG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.currentStep").value("READY"));

        verify(onboardingService).generateBatches(eq(TASK_CONFIG_ID), any(GenerateTaskRunBatchRequest.class));
        verifyNoInteractions(taskConfigService);
    }

    @Test
    void legacyResultGenerationRejectsInitialWorkflowStateThroughOnboardingGate() throws Exception {
        when(onboardingService.generateResults(TASK_CONFIG_ID))
                .thenThrow(new TaskOnboardingStateException("Generation retry does not match the current step"));

        mockMvc.perform(post("/api/task/{id}/generate-results", TASK_CONFIG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(7))
                .andExpect(jsonPath("$.msg").value(
                        "服务异常: Generation retry does not match the current step"));

        verify(onboardingService).generateResults(TASK_CONFIG_ID);
        verifyNoInteractions(taskConfigService);
    }

    @Test
    void legacyBatchGenerationRejectsLockedWorkflowStateThroughOnboardingGate() throws Exception {
        when(onboardingService.generateBatches(eq(TASK_CONFIG_ID), any(GenerateTaskRunBatchRequest.class)))
                .thenThrow(new TaskOnboardingStateException("Task onboarding cannot retry generation"));

        mockMvc.perform(post("/api/task/{id}/generate-run-batches", TASK_CONFIG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(7))
                .andExpect(jsonPath("$.msg").value(
                        "服务异常: Task onboarding cannot retry generation"));

        verify(onboardingService).generateBatches(eq(TASK_CONFIG_ID), any(GenerateTaskRunBatchRequest.class));
        verifyNoInteractions(taskConfigService);
    }

    private GenerateTaskRunBatchRequest batchRequest() {
        GenerateTaskRunBatchRequest request = new GenerateTaskRunBatchRequest();
        request.setBatchSize(3);
        request.setCliId("codex");
        return request;
    }

    private TaskOnboardingResponse response(String currentStep) {
        TaskOnboardingResponse response = new TaskOnboardingResponse();
        response.setCurrentStep(currentStep);
        return response;
    }
}
