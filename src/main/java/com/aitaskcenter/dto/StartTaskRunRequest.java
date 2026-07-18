package com.aitaskcenter.dto;

import java.util.List;

public class StartTaskRunRequest {
    // 字段：要启动的任务执行记录 ID 列表
    private List<Long> taskRunIds;
    // 字段：Python Worker 使用的并发模式
    private String executionMode;
    // 字段：Python Worker 并发线程或进程数量
    private Integer workerCount;

    // 方法：getTaskRunIds
    public List<Long> getTaskRunIds() {
        return taskRunIds;
    }

    // 方法：setTaskRunIds
    public void setTaskRunIds(List<Long> taskRunIds) {
        this.taskRunIds = taskRunIds;
    }

    // 方法：getExecutionMode
    public String getExecutionMode() {
        return executionMode;
    }

    // 方法：setExecutionMode
    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    // 方法：getWorkerCount
    public Integer getWorkerCount() {
        return workerCount;
    }

    // 方法：setWorkerCount
    public void setWorkerCount(Integer workerCount) {
        this.workerCount = workerCount;
    }
}
