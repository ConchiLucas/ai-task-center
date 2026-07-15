package com.aitaskcenter.dto;

import com.aitaskcenter.model.TaskConfig;

public record TaskOnboardingTaskSummary(
        Long id,
        String taskName,
        Long projectId,
        String cliId,
        Long databaseConfigId,
        String taskDesc,
        String selectedTables) {
    public static TaskOnboardingTaskSummary from(TaskConfig task) {
        return new TaskOnboardingTaskSummary(
                task.getId(), task.getTaskName(), task.getProjectId(), task.getCliId(),
                task.getDatabaseConfigId(), task.getTaskDesc(), task.getSelectedTables());
    }
}
