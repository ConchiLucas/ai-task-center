package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tb_task_run")
public class TaskRun extends BaseEntity {
    @Column(nullable = false)
    // 字段：任务执行名称
    private String taskName;

    // 字段：来源任务配置 ID
    private Long taskConfigId;

    @Column(nullable = false)
    // 字段：所属项目 ID
    private Long projectId;

    @Column(nullable = false)
    // 字段：执行 CLI 配置 ID
    private String cliId;

    // 字段：执行使用的数据库配置 ID
    private Long databaseConfigId;

    @Column(length = 4000)
    // 字段：执行使用的数据表 JSON
    private String selectedTables;

    @Column(nullable = false)
    // 字段：任务执行状态
    private String status = "PENDING";

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

    // 方法：getCliId
    public String getCliId() {
        return cliId;
    }

    // 方法：setCliId
    public void setCliId(String cliId) {
        this.cliId = cliId;
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
}
