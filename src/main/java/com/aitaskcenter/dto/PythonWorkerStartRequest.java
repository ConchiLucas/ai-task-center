package com.aitaskcenter.dto;

import java.util.List;

public class PythonWorkerStartRequest {
    // 字段：本次执行选择的 CLI 配置 ID
    private String cliId;
    // 字段：Python Worker 并发模式
    private String executionMode;
    // 字段：Python Worker 并发线程或进程数量
    private Integer workerCount;
    // 字段：任务执行记录快照
    private List<PythonWorkerTaskRunItem> taskRuns;

    // 方法：getCliId
    public String getCliId() {
        return cliId;
    }

    // 方法：setCliId
    public void setCliId(String cliId) {
        this.cliId = cliId;
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

    // 方法：getTaskRuns
    public List<PythonWorkerTaskRunItem> getTaskRuns() {
        return taskRuns;
    }

    // 方法：setTaskRuns
    public void setTaskRuns(List<PythonWorkerTaskRunItem> taskRuns) {
        this.taskRuns = taskRuns;
    }
}
