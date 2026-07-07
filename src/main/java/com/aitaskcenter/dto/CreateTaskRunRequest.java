package com.aitaskcenter.dto;

public class CreateTaskRunRequest {
    private Long taskConfigId;
    private String taskName;

    // 方法：getTaskConfigId
    public Long getTaskConfigId() {
        return taskConfigId;
    }

    // 方法：setTaskConfigId
    public void setTaskConfigId(Long taskConfigId) {
        this.taskConfigId = taskConfigId;
    }

    // 方法：getTaskName
    public String getTaskName() {
        return taskName;
    }

    // 方法：setTaskName
    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }
}
