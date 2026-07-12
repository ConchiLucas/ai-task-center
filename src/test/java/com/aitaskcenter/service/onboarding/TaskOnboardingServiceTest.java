package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskOnboardingServiceTest {
    @Test
    void initializesUnconfiguredTaskAtResultCodeStep() {
        TaskConfig task = new TaskConfig();
        assertEquals("RESULT_CODE", task.getOnboardingStep());
        assertEquals("ACTIVE", task.getOnboardingStatus());
        assertEquals("{}", task.getOnboardingContext());
    }

    @Test
    void initializesJacksonContextWithEmptyDefaults() throws Exception {
        TaskOnboardingContext context = new ObjectMapper().readValue("{}", TaskOnboardingContext.class);

        assertEquals("", context.getResultValidationRunId());
        assertEquals("", context.getResultReportToken());
        assertEquals(0, context.getResultValidationIds().size());
        assertEquals("", context.getResultArtifactPath());
        assertEquals("", context.getResultArtifactHash());
        assertEquals("", context.getBatchValidationMarker());
        assertEquals("", context.getBatchReportToken());
        assertNull(context.getBatchValidationTaskRunId());
        assertEquals(0, context.getBatchValidationResultIds().size());
        assertEquals("", context.getBatchArtifactPath());
        assertEquals("", context.getBatchArtifactHash());
        assertEquals("", context.getErrorMessage());
    }

    @Test
    void roundTripsFullyPopulatedContextWithJackson() throws Exception {
        TaskOnboardingContext context = new TaskOnboardingContext();
        context.setResultValidationRunId("result-run-1");
        context.setResultReportToken("result-token");
        context.setResultValidationIds(List.of(11L, 12L));
        context.setResultArtifactPath("/tmp/result.json");
        context.setResultArtifactHash("result-hash");
        context.setBatchValidationMarker("batch-marker");
        context.setBatchReportToken("batch-token");
        context.setBatchValidationTaskRunId(21L);
        context.setBatchValidationResultIds(List.of(31L, 32L));
        context.setBatchArtifactPath("/tmp/batch.json");
        context.setBatchArtifactHash("batch-hash");
        context.setErrorMessage("validation failed");

        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(context);
        TaskOnboardingContext restored = objectMapper.readValue(json, TaskOnboardingContext.class);

        assertEquals("result-run-1", restored.getResultValidationRunId());
        assertEquals("result-token", restored.getResultReportToken());
        assertEquals(List.of(11L, 12L), restored.getResultValidationIds());
        assertEquals("/tmp/result.json", restored.getResultArtifactPath());
        assertEquals("result-hash", restored.getResultArtifactHash());
        assertEquals("batch-marker", restored.getBatchValidationMarker());
        assertEquals("batch-token", restored.getBatchReportToken());
        assertEquals(21L, restored.getBatchValidationTaskRunId());
        assertEquals(List.of(31L, 32L), restored.getBatchValidationResultIds());
        assertEquals("/tmp/batch.json", restored.getBatchArtifactPath());
        assertEquals("batch-hash", restored.getBatchArtifactHash());
        assertEquals("validation failed", restored.getErrorMessage());
    }
}
