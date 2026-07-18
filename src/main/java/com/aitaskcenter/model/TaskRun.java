package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "tb_task_run",
        indexes = {
                @Index(name = "idx_task_run_queue", columnList = "status,next_retry_at,created_at"),
                @Index(name = "idx_task_run_dispatch_group", columnList = "dispatch_group_id,status"),
                @Index(name = "idx_task_run_task_record_type", columnList = "task_config_id,record_type,id"),
                @Index(name = "idx_task_run_lease", columnList = "status,lease_until")
        })
public class TaskRun extends BaseEntity {
    @Column(nullable = false)
    // 字段：任务执行名称
    private String taskName;

    // 字段：来源任务配置 ID
    private Long taskConfigId;

    @Column(nullable = false)
    // 字段：所属项目 ID
    private Long projectId;

    @Column(length = 100)
    private String handlerKey;

    @Column(length = 30)
    private String executorType;

    @Column(length = 128)
    private String executorId;

    // 字段：执行使用的数据库配置 ID
    private Long databaseConfigId;

    @Column(length = 4000)
    // 字段：执行使用的数据表 JSON
    private String selectedTables;

    @Column(nullable = false)
    // 字段：任务执行状态
    private String status = "PENDING";

    @Column(nullable = false, length = 30, columnDefinition = "varchar(30) default 'FORMAL'")
    // 字段：正式批次、当前验证批次或历史验证批次
    private String recordType = TaskRecordType.FORMAL;

    // 字段：开始执行时间
    private OffsetDateTime startTime;
    // 字段：结束执行时间
    private OffsetDateTime endTime;
    // 字段：执行耗时秒数
    private Integer durationSeconds;

    @Column(length = 1000)
    // 字段：状态原因或失败原因
    private String reason;

    @Column(length = 1000)
    // 字段：任务日志文件路径
    private String logPath;

    @Column(length = 4000)
    // 字段：任务执行日志内容
    private String runLog;

    @Column(columnDefinition = "text")
    // 字段：批次级发送给 AI 的 JSON 提示词
    private String aiPromptJson;

    @Column(columnDefinition = "text")
    // 字段：批次级 AI 原始响应 JSON
    private String aiResponseJson;

    // 字段：Python Worker 使用的执行模式
    private String executionMode = "thread";

    // 字段：本次提交允许的最大并发数量
    private Integer requestedWorkerCount = 1;

    @Column(length = 64)
    // 字段：同一次批量提交生成的调度组 ID
    private String dispatchGroupId;

    // 字段：已经领取执行的次数
    private Integer attemptNo = 0;

    // 字段：允许自动执行的最大次数
    private Integer maxAttempts = 3;

    // 字段：失败任务允许再次领取的时间
    private OffsetDateTime nextRetryAt;

    // 字段：当前 Worker 对任务的租约到期时间
    private OffsetDateTime leaseUntil;

    @Column(length = 64)
    // 字段：当前领取任务的唯一令牌
    private String claimToken;

    @Column(length = 128)
    // 字段：当前执行任务的 Worker 标识
    private String workerId;

    // 字段：当前 Worker 最近一次续租心跳时间
    private OffsetDateTime heartbeatAt;

    // 字段：批次预期包含的任务结果数量
    private Integer expectedResultCount = 0;

    // 方法：getTaskName
    public String getTaskName() {
        return taskName;
    }

    // 方法：setTaskName
    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    // 方法：getTaskConfigId
    public Long getTaskConfigId() {
        return taskConfigId;
    }

    // 方法：setTaskConfigId
    public void setTaskConfigId(Long taskConfigId) {
        this.taskConfigId = taskConfigId;
    }

    // 方法：getProjectId
    public Long getProjectId() {
        return projectId;
    }

    // 方法：setProjectId
    public void setProjectId(Long projectId) {
        this.projectId = projectId;
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

    // 方法：getDatabaseConfigId
    public Long getDatabaseConfigId() {
        return databaseConfigId;
    }

    // 方法：setDatabaseConfigId
    public void setDatabaseConfigId(Long databaseConfigId) {
        this.databaseConfigId = databaseConfigId;
    }

    // 方法：getSelectedTables
    public String getSelectedTables() {
        return selectedTables;
    }

    // 方法：setSelectedTables
    public void setSelectedTables(String selectedTables) {
        this.selectedTables = selectedTables;
    }

    // 方法：getStatus
    public String getStatus() {
        return status;
    }

    // 方法：setStatus
    public void setStatus(String status) {
        this.status = status;
    }

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
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

    // 方法：getLogPath
    public String getLogPath() {
        return logPath;
    }

    // 方法：setLogPath
    public void setLogPath(String logPath) {
        this.logPath = logPath;
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

    // 方法：getExecutionMode
    public String getExecutionMode() {
        return executionMode;
    }

    // 方法：setExecutionMode
    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    // 方法：getRequestedWorkerCount
    public Integer getRequestedWorkerCount() {
        return requestedWorkerCount;
    }

    // 方法：setRequestedWorkerCount
    public void setRequestedWorkerCount(Integer requestedWorkerCount) {
        this.requestedWorkerCount = requestedWorkerCount;
    }

    // 方法：getDispatchGroupId
    public String getDispatchGroupId() {
        return dispatchGroupId;
    }

    // 方法：setDispatchGroupId
    public void setDispatchGroupId(String dispatchGroupId) {
        this.dispatchGroupId = dispatchGroupId;
    }

    // 方法：getAttemptNo
    public Integer getAttemptNo() {
        return attemptNo;
    }

    // 方法：setAttemptNo
    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    // 方法：getMaxAttempts
    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    // 方法：setMaxAttempts
    public void setMaxAttempts(Integer maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    // 方法：getNextRetryAt
    public OffsetDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    // 方法：setNextRetryAt
    public void setNextRetryAt(OffsetDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    // 方法：getLeaseUntil
    public OffsetDateTime getLeaseUntil() {
        return leaseUntil;
    }

    // 方法：setLeaseUntil
    public void setLeaseUntil(OffsetDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    // 方法：getClaimToken
    public String getClaimToken() {
        return claimToken;
    }

    // 方法：setClaimToken
    public void setClaimToken(String claimToken) {
        this.claimToken = claimToken;
    }

    // 方法：getWorkerId
    public String getWorkerId() {
        return workerId;
    }

    // 方法：setWorkerId
    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    // 方法：getHeartbeatAt
    public OffsetDateTime getHeartbeatAt() {
        return heartbeatAt;
    }

    // 方法：setHeartbeatAt
    public void setHeartbeatAt(OffsetDateTime heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    // 方法：getExpectedResultCount
    public Integer getExpectedResultCount() {
        return expectedResultCount;
    }

    // 方法：setExpectedResultCount
    public void setExpectedResultCount(Integer expectedResultCount) {
        this.expectedResultCount = expectedResultCount;
    }
}
