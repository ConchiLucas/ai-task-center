package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_interface_project")
public class ProjectConfig extends BaseEntity {
    @Column(nullable = false, unique = true)
    // 字段：项目名称
    private String projectName;

    @Column(length = 1000)
    // 字段：项目描述
    private String projectDesc;

    // 字段：配置所属用户
    private String userName = "local";

    // 方法：getProjectName
    public String getProjectName() {
        return projectName;
    }

    // 方法：setProjectName
    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    // 方法：getProjectDesc
    public String getProjectDesc() {
        return projectDesc;
    }

    // 方法：setProjectDesc
    public void setProjectDesc(String projectDesc) {
        this.projectDesc = projectDesc;
    }

    // 方法：getUserName
    public String getUserName() {
        return userName;
    }

    // 方法：setUserName
    public void setUserName(String userName) {
        this.userName = userName;
    }
}
