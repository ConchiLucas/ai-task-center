package com.aitaskcenter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TaskRunReference {
    @JsonProperty("ID")
    // 字段：关联任务批次 ID
    private Long id;

    // 字段：关联任务批次名称
    private String taskName;

    // 字段：关联任务批次状态
    private String status;

    // 方法：TaskRunReference
    public TaskRunReference(Long id, String taskName, String status) {
        this.id = id;
        this.taskName = taskName;
        this.status = status;
    }

    // 方法：getId
    public Long getId() {
        return id;
    }

    // 方法：setId
    public void setId(Long id) {
        this.id = id;
    }

    // 方法：getTaskName
    public String getTaskName() {
        return taskName;
    }

    // 方法：setTaskName
    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    // 方法：getStatus
    public String getStatus() {
        return status;
    }

    // 方法：setStatus
    public void setStatus(String status) {
        this.status = status;
    }
}
