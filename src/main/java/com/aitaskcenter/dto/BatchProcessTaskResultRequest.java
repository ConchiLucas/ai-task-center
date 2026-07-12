package com.aitaskcenter.dto;

import java.util.List;

public class BatchProcessTaskResultRequest {
    // 字段：要批量处理的任务结果 ID 列表
    private List<Long> taskResultIds;
    // 字段：本次批量执行选择的 CLI 配置 ID
    private String cliId;
    // 字段：Python Worker 并发线程数量
    private Integer workerCount;

    // 方法：getTaskResultIds
    public List<Long> getTaskResultIds() {
        return taskResultIds;
    }

    // 方法：setTaskResultIds
    public void setTaskResultIds(List<Long> taskResultIds) {
        this.taskResultIds = taskResultIds;
    }

    // 方法：getCliId
    public String getCliId() {
        return cliId;
    }

    // 方法：setCliId
    public void setCliId(String cliId) {
        this.cliId = cliId;
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
