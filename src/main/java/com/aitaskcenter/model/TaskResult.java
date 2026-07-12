package com.aitaskcenter.model;

import com.aitaskcenter.dto.TaskRunReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tb_task_result")
public class TaskResult extends BaseEntity {
    @Column(nullable = false)
    // 字段：任务结果名称
    private String resultName;

    // 字段：关联任务执行记录 ID
    private Long taskRunId;
    // 字段：关联任务配置 ID
    private Long taskConfigId;

    @Column(nullable = false)
    // 字段：所属项目 ID
    private Long projectId;

    // 字段：执行 CLI 配置 ID
    private String cliId;
    // 字段：结果来源数据库配置 ID
    private Long databaseConfigId;

    @Column(length = 4000)
    // 字段：结果来源数据表 JSON
    private String sourceTables;

    @Column(length = 1000)
    // 字段：解析来源说明
    private String sourceDescription;

    @Column(nullable = false)
    // 字段：任务结果状态
    private String status = "PENDING";

    @Column(length = 2000)
    // 字段：任务结果摘要
    private String summary;

    @Column(columnDefinition = "text")
    // 字段：任务结果正文内容
    private String resultContent;

    @Column(length = 4000)
    // 字段：任务结果错误信息
    private String errorMessage;

    // 字段：解析插入时间
    private OffsetDateTime parsedAt;
    // 字段：结果完成时间
    private OffsetDateTime completedAt;

    @Transient
    // 字段：通过批次关联表查询到的真实关联任务列表
    private List<TaskRunReference> relatedTaskRuns = new ArrayList<>();

    // 方法：getResultName
    public String getResultName() {
        return resultName;
    }

    // 方法：setResultName
    public void setResultName(String resultName) {
        this.resultName = resultName;
    }

    // 方法：getTaskRunId
    public Long getTaskRunId() {
        return taskRunId;
    }

    // 方法：setTaskRunId
    public void setTaskRunId(Long taskRunId) {
        this.taskRunId = taskRunId;
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

    // 方法：getSourceTables
    public String getSourceTables() {
        return sourceTables;
    }

    // 方法：setSourceTables
    public void setSourceTables(String sourceTables) {
        this.sourceTables = sourceTables;
    }

    // 方法：getSourceDescription
    public String getSourceDescription() {
        return sourceDescription;
    }

    // 方法：setSourceDescription
    public void setSourceDescription(String sourceDescription) {
        this.sourceDescription = sourceDescription;
    }

    // 方法：getStatus
    public String getStatus() {
        return status;
    }

    // 方法：setStatus
    public void setStatus(String status) {
        this.status = status;
    }

    // 方法：getSummary
    public String getSummary() {
        return summary;
    }

    // 方法：setSummary
    public void setSummary(String summary) {
        this.summary = summary;
    }

    // 方法：getResultContent
    public String getResultContent() {
        return resultContent;
    }

    // 方法：setResultContent
    public void setResultContent(String resultContent) {
        this.resultContent = resultContent;
    }

    // 方法：getErrorMessage
    public String getErrorMessage() {
        return errorMessage;
    }

    // 方法：setErrorMessage
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // 方法：getParsedAt
    public OffsetDateTime getParsedAt() {
        return parsedAt;
    }

    // 方法：setParsedAt
    public void setParsedAt(OffsetDateTime parsedAt) {
        this.parsedAt = parsedAt;
    }

    // 方法：getCompletedAt
    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    // 方法：setCompletedAt
    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }

    // 方法：getRelatedTaskRuns
    public List<TaskRunReference> getRelatedTaskRuns() {
        return relatedTaskRuns;
    }

    // 方法：setRelatedTaskRuns
    public void setRelatedTaskRuns(List<TaskRunReference> relatedTaskRuns) {
        this.relatedTaskRuns = relatedTaskRuns == null ? new ArrayList<>() : relatedTaskRuns;
    }
}
