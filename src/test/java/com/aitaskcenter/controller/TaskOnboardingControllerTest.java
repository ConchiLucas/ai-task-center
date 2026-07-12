package com.aitaskcenter.controller;

import com.aitaskcenter.dto.GenerateTaskRunBatchRequest;
import com.aitaskcenter.dto.TaskOnboardingReportRequest;
import com.aitaskcenter.dto.TaskOnboardingResponse;
import com.aitaskcenter.service.onboarding.TaskOnboardingService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskOnboardingControllerTest {
    private static final long TASK_CONFIG_ID = 42L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TaskOnboardingService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(TaskOnboardingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskOnboardingController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void getOnboardingDelegatesAndUsesApiEnvelope() throws Exception {
        when(service.get(TASK_CONFIG_ID)).thenReturn(response("RESULT_CODE"));

        mockMvc.perform(get("/api/task/{id}/onboarding", TASK_CONFIG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.currentStep").value("RESULT_CODE"));

        verify(service).get(TASK_CONFIG_ID);
    }

    @Test
    void reportDelegatesAndUsesApiEnvelope() throws Exception {
        TaskOnboardingReportRequest request = reportRequest();
        when(service.report(eq(TASK_CONFIG_ID), any(TaskOnboardingReportRequest.class)))
                .thenReturn(response("RESULT_VALIDATION"));

        mockMvc.perform(post("/api/task/{id}/onboarding/report", TASK_CONFIG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.currentStep").value("RESULT_VALIDATION"));

        verify(service).report(eq(TASK_CONFIG_ID), any(TaskOnboardingReportRequest.class));
    }

    @Test
    void invalidReportUsesExistingApiExceptionHandlerEnvelope() throws Exception {
        when(service.report(eq(TASK_CONFIG_ID), any(TaskOnboardingReportRequest.class)))
                .thenThrow(new IllegalArgumentException("Callback token is invalid"));

        mockMvc.perform(post("/api/task/{id}/onboarding/report", TASK_CONFIG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reportRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(7))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.msg").value("Callback token is invalid"));
    }

    @Test
    void confirmResultValidationDelegatesAndUsesApiEnvelope() throws Exception {
        when(service.confirmResultValidation(TASK_CONFIG_ID)).thenReturn(response("RESULT_GENERATION"));

        mockMvc.perform(post("/api/task/{id}/onboarding/result-validation/confirm", TASK_CONFIG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.currentStep").value("RESULT_GENERATION"));

        verify(service).confirmResultValidation(TASK_CONFIG_ID);
    }

    @Test
    void generateResultsDelegatesAndUsesApiEnvelope() throws Exception {
        when(service.generateResults(TASK_CONFIG_ID)).thenReturn(response("BATCH_CODE"));

        mockMvc.perform(post("/api/task/{id}/onboarding/results/generate", TASK_CONFIG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.currentStep").value("BATCH_CODE"));

        verify(service).generateResults(TASK_CONFIG_ID);
    }

    @Test
    void confirmBatchValidationDelegatesAndUsesApiEnvelope() throws Exception {
        when(service.confirmBatchValidation(TASK_CONFIG_ID)).thenReturn(response("BATCH_GENERATION"));

        mockMvc.perform(post("/api/task/{id}/onboarding/batch-validation/confirm", TASK_CONFIG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.currentStep").value("BATCH_GENERATION"));

        verify(service).confirmBatchValidation(TASK_CONFIG_ID);
    }

    @Test
    void generateBatchesDelegatesExistingRequestAndUsesApiEnvelope() throws Exception {
        GenerateTaskRunBatchRequest request = new GenerateTaskRunBatchRequest();
        request.setBatchSize(3);
        request.setCliId("codex");
        when(service.generateBatches(eq(TASK_CONFIG_ID), any(GenerateTaskRunBatchRequest.class)))
                .thenReturn(response("COMPLETED"));

        mockMvc.perform(post("/api/task/{id}/onboarding/batches/generate", TASK_CONFIG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.currentStep").value("COMPLETED"));

        verify(service).generateBatches(eq(TASK_CONFIG_ID), any(GenerateTaskRunBatchRequest.class));
    }

    private TaskOnboardingReportRequest reportRequest() {
        TaskOnboardingReportRequest request = new TaskOnboardingReportRequest();
        request.setStage("result");
        request.setToken("callback-token");
        request.setArtifact("src/main/java/Generator.java");
        request.setArtifactHash("a".repeat(64));
        request.setEntityIds(java.util.List.of(100L));
        return request;
    }

    private TaskOnboardingResponse response(String currentStep) {
        TaskOnboardingResponse response = new TaskOnboardingResponse();
        response.setCurrentStep(currentStep);
        return response;
    }
}
