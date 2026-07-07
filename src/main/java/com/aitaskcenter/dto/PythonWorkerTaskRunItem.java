package com.aitaskcenter.dto;

public class PythonWorkerTaskRunItem {
    // 字段：任务执行记录 ID
    private Long id;
    // 字段：任务名称
    private String taskName;
    // 字段：任务配置 ID
    private Long taskConfigId;
    // 字段：项目 ID
    private Long projectId;
    // 字段：数据库配置 ID
    private Long databaseConfigId;
    // 字段：选中的数据表 JSON
    private String selectedTables;

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
}
