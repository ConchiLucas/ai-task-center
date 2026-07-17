package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "tb_task_execution_log",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_task_execution_log_run_attempt",
                columnNames = {"task_run_id", "attempt_no"}))
public class TaskExecutionLog extends BaseEntity {
    // 字段：所属任务批次 ID
    @Column(nullable = false)
    private Long taskRunId;

    // 字段：同一任务内的执行次数
    @Column(nullable = false)
    private Integer attemptNo;

    // 字段：本次执行使用的 CLI 配置 ID
    @Column(nullable = false)
    private String cliId;

    @Column(length = 100)
    private String handlerKey;

    @Column(length = 30)
    private String executorType;

    @Column(length = 128)
    private String executorId;

    @Column(length = 255)
    private String executorLabel;

    // 字段：本次执行并发模式
    @Column(nullable = false)
    private String executionMode;

    // 字段：本次执行使用的线程或进程数量
    @Column(nullable = false)
    private Integer workerCount;

    // 字段：本次执行状态
    @Column(nullable = false)
    private String status;

    // 字段：本次执行开始时间
    private OffsetDateTime startTime;

    // 字段：本次执行结束时间
    private OffsetDateTime endTime;

    // 字段：本次执行耗时秒数
    private Integer durationSeconds;

    // 字段：本次执行结果说明或失败原因
    @Column(length = 1000)
    private String reason;

    // 字段：本次执行日志正文
    @Column(length = 4000)
    private String runLog;

    // 字段：本次发送给 AI 的 JSON 提示词快照
    @Column(columnDefinition = "text")
    private String aiPromptJson;

    // 字段：本次 AI 原始响应 JSON 快照
    @Column(columnDefinition = "text")
    private String aiResponseJson;

    // 方法：getTaskRunId
    public Long getTaskRunId() {
        return taskRunId;
    }

    // 方法：setTaskRunId
    public void setTaskRunId(Long taskRunId) {
        this.taskRunId = taskRunId;
    }

    // 方法：getAttemptNo
    public Integer getAttemptNo() {
        return attemptNo;
    }

    // 方法：setAttemptNo
    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    // 方法：getCliId
    public String getCliId() {
        return cliId;
    }

    // 方法：setCliId
    public void setCliId(String cliId) {
        this.cliId = cliId;
    }

    public String getHandlerKey() {
        return handlerKey;
    }

    public void setHandlerKey(String handlerKey) {
        this.handlerKey = handlerKey;
    }

    public String getExecutorType() {
        return executorType;
    }

    public void setExecutorType(String executorType) {
        this.executorType = executorType;
    }

    public String getExecutorId() {
        return executorId;
    }

    public void setExecutorId(String executorId) {
        this.executorId = executorId;
    }

    public String getExecutorLabel() {
        return executorLabel;
    }

    public void setExecutorLabel(String executorLabel) {
        this.executorLabel = executorLabel;
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

    // 方法：getStatus
    public String getStatus() {
        return status;
    }

    // 方法：setStatus
    public void setStatus(String status) {
        this.status = status;
    }

    // 方法：getStartTime
    public OffsetDateTime getStartTime() {
        return startTime;
    }

    // 方法：setStartTime
    public void setStartTime(OffsetDateTime startTime) {
        this.startTime = startTime;
    }

    // 方法：getEndTime
    public OffsetDateTime getEndTime() {
        return endTime;
    }

    // 方法：setEndTime
    public void setEndTime(OffsetDateTime endTime) {
        this.endTime = endTime;
    }

    // 方法：getDurationSeconds
    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    // 方法：setDurationSeconds
    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    // 方法：getReason
    public String getReason() {
        return reason;
    }

    // 方法：setReason
    public void setReason(String reason) {
        this.reason = reason;
    }

    // 方法：getRunLog
    public String getRunLog() {
        return runLog;
    }

    // 方法：setRunLog
    public void setRunLog(String runLog) {
        this.runLog = runLog;
    }

    // 方法：getAiPromptJson
    public String getAiPromptJson() {
        return aiPromptJson;
    }

    // 方法：setAiPromptJson
    public void setAiPromptJson(String aiPromptJson) {
        this.aiPromptJson = aiPromptJson;
    }

    // 方法：getAiResponseJson
    public String getAiResponseJson() {
        return aiResponseJson;
    }

    // 方法：setAiResponseJson
    public void setAiResponseJson(String aiResponseJson) {
        this.aiResponseJson = aiResponseJson;
    }
}
