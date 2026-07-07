package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_connection")
public class ConnectionConfig extends BaseEntity {
    @Column(nullable = false)
    // 字段：连接名称
    private String connectionName;

    @Column(nullable = false)
    // 字段：数据库类型
    private String connectionType;

    @Column(nullable = false)
    // 字段：数据库 Host 地址
    private String connectionUrl;

    @Column(nullable = false)
    // 字段：所属项目分组 ID
    private String connectionGroup;

    @Column(nullable = false)
    // 字段：数据库名称
    private String databaseName;

    // 字段：数据库端口
    private Integer port;

    @Column(nullable = false)
    // 字段：数据库登录用户名
    private String dbLoginName;

    // 字段：数据库登录密码
    private String dbLoginPassword;
    // 字段：配置所属用户
    private String userName = "local";
    // 字段：部署环境名称
    private String envName;

    // 方法：getConnectionName
    public String getConnectionName() {
        return connectionName;
    }

    // 方法：setConnectionName
    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    // 方法：getConnectionType
    public String getConnectionType() {
        return connectionType;
    }

    // 方法：setConnectionType
    public void setConnectionType(String connectionType) {
        this.connectionType = connectionType;
    }

    // 方法：getConnectionUrl
    public String getConnectionUrl() {
        return connectionUrl;
    }

    // 方法：setConnectionUrl
    public void setConnectionUrl(String connectionUrl) {
        this.connectionUrl = connectionUrl;
    }

    // 方法：getConnectionGroup
    public String getConnectionGroup() {
        return connectionGroup;
    }

    // 方法：setConnectionGroup
    public void setConnectionGroup(String connectionGroup) {
        this.connectionGroup = connectionGroup;
    }

    // 方法：getDatabaseName
    public String getDatabaseName() {
        return databaseName;
    }

    // 方法：setDatabaseName
    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    // 方法：getPort
    public Integer getPort() {
        return port;
    }

    // 方法：setPort
    public void setPort(Integer port) {
        this.port = port;
    }

    // 方法：getDbLoginName
    public String getDbLoginName() {
        return dbLoginName;
    }

    // 方法：setDbLoginName
    public void setDbLoginName(String dbLoginName) {
        this.dbLoginName = dbLoginName;
    }

    // 方法：getDbLoginPassword
    public String getDbLoginPassword() {
        return dbLoginPassword;
    }

    // 方法：setDbLoginPassword
    public void setDbLoginPassword(String dbLoginPassword) {
        this.dbLoginPassword = dbLoginPassword;
    }

    // 方法：getUserName
    public String getUserName() {
        return userName;
    }

    // 方法：setUserName
    public void setUserName(String userName) {
        this.userName = userName;
    }

    // 方法：getEnvName
    public String getEnvName() {
        return envName;
    }

    // 方法：setEnvName
    public void setEnvName(String envName) {
        this.envName = envName;
    }
}
