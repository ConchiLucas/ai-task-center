package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
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
}
