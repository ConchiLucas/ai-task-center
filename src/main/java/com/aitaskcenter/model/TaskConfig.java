package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_task_config")
public class TaskConfig extends BaseEntity {
    @Column(nullable = false)
    // 字段：任务配置名称
    private String taskName;

    @Column(nullable = false)
    // 字段：所属项目 ID
    private Long projectId;

    @Column
    // 字段：默认执行 CLI 配置 ID
    private String cliId;

    @Column(length = 100)
    private String handlerKey;

    @Column(length = 30)
    private String executorType;

    @Column(length = 128)
    private String executorId;

    @Column(length = 128)
    private String onboardingCliId;

    // 字段：关联数据库配置 ID
    private Long databaseConfigId;

    @Column(length = 1000)
    // 字段：任务描述
    private String taskDesc;

    @Column(length = 4000)
    // 字段：任务关联的数据表 JSON
    private String selectedTables;

    @Column(nullable = false, length = 40, columnDefinition = "varchar(40) default 'TARGET_SELECTION'")
    private String onboardingStep = "TARGET_SELECTION";

    @Column(nullable = false, length = 40, columnDefinition = "varchar(40) default 'ACTIVE'")
    private String onboardingStatus = "ACTIVE";

    @Column(nullable = false, columnDefinition = "text default '{}'")
    private String onboardingContext = "{}";

    // 方法：getTaskName
    public String getTaskName() {
        return taskName;
    }

    // 方法：setTaskName
    public void setTaskName(String taskName) {
        this.taskName = taskName;
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

    public String getOnboardingCliId() {
        return onboardingCliId;
    }

    public void setOnboardingCliId(String onboardingCliId) {
        this.onboardingCliId = onboardingCliId;
    }

    // 方法：getDatabaseConfigId
    public Long getDatabaseConfigId() {
        return databaseConfigId;
    }

    // 方法：setDatabaseConfigId
    public void setDatabaseConfigId(Long databaseConfigId) {
        this.databaseConfigId = databaseConfigId;
    }

    // 方法：getTaskDesc
    public String getTaskDesc() {
        return taskDesc;
    }

    // 方法：setTaskDesc
    public void setTaskDesc(String taskDesc) {
        this.taskDesc = taskDesc;
    }

    // 方法：getSelectedTables
    public String getSelectedTables() {
        return selectedTables;
    }

    // 方法：setSelectedTables
    public void setSelectedTables(String selectedTables) {
        this.selectedTables = selectedTables;
    }

    public String getOnboardingStep() {
        return onboardingStep;
    }

    public void setOnboardingStep(String onboardingStep) {
        this.onboardingStep = onboardingStep;
    }

    public String getOnboardingStatus() {
        return onboardingStatus;
    }

    public void setOnboardingStatus(String onboardingStatus) {
        this.onboardingStatus = onboardingStatus;
    }

    public String getOnboardingContext() {
        return onboardingContext;
    }

    public void setOnboardingContext(String onboardingContext) {
        this.onboardingContext = onboardingContext;
    }
}
