package com.aitaskcenter.dto;

import com.aitaskcenter.model.TaskRun;

public record TaskOnboardingRunSummary(
        Long id,
        String taskName,
        String status,
        String reason,
        String cliId,
        String aiPromptJson,
        Integer expectedResultCount) {
    public static TaskOnboardingRunSummary from(TaskRun run) {
        return new TaskOnboardingRunSummary(
                run.getId(),
                run.getTaskName(),
                run.getStatus(),
                run.getReason(),
                run.getCliId(),
                run.getAiPromptJson(),
                run.getExpectedResultCount());
    }
}
