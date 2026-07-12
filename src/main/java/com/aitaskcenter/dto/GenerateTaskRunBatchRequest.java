package com.aitaskcenter.dto;

public class GenerateTaskRunBatchRequest {
    // 字段：每个执行批次包含的任务结果数量
    private Integer batchSize;
    // 字段：批次默认执行 CLI 配置 ID
    private String cliId;
    // 字段：批次任务名称前缀
    private String taskNamePrefix;
    // 字段：是否纳入失败任务结果用于重试
    private Boolean includeFailed;

    // 方法：getBatchSize
    public Integer getBatchSize() {
        return batchSize;
    }

    // 方法：setBatchSize
    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    // 方法：getCliId
    public String getCliId() {
        return cliId;
    }

    // 方法：setCliId
    public void setCliId(String cliId) {
        this.cliId = cliId;
    }

    // 方法：getTaskNamePrefix
    public String getTaskNamePrefix() {
        return taskNamePrefix;
    }

    // 方法：setTaskNamePrefix
    public void setTaskNamePrefix(String taskNamePrefix) {
        this.taskNamePrefix = taskNamePrefix;
    }

    // 方法：getIncludeFailed
    public Boolean getIncludeFailed() {
        return includeFailed;
    }

    // 方法：setIncludeFailed
    public void setIncludeFailed(Boolean includeFailed) {
        this.includeFailed = includeFailed;
    }
}
