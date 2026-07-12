package com.aitaskcenter.dto;

import com.aitaskcenter.model.TaskResult;

public record TaskOnboardingResultSummary(
        Long id,
        String resultName,
        String status,
        String summary,
        String resultContent,
        String sourceDescription) {
    public static TaskOnboardingResultSummary from(TaskResult result) {
        return new TaskOnboardingResultSummary(
                result.getId(),
                result.getResultName(),
                result.getStatus(),
                result.getSummary(),
                result.getResultContent(),
                result.getSourceDescription());
    }
}
