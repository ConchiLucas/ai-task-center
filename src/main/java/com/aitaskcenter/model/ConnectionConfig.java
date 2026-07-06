package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_connection")
public class ConnectionConfig extends BaseEntity {
    @Column(nullable = false)
    private String connectionName;

    @Column(nullable = false)
    private String connectionType;

    @Column(nullable = false)
    private String connectionUrl;

    @Column(nullable = false)
    private String connectionGroup;

    @Column(nullable = false)
    private String databaseName;

    private Integer port;

    @Column(nullable = false)
    private String dbLoginName;

    private String dbLoginPassword;
    private String userName = "local";
    private String envName;

    public String getConnectionName() {
        return connectionName;
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getConnectionType() {
        return connectionType;
    }

    public void setConnectionType(String connectionType) {
        this.connectionType = connectionType;
    }

    public String getConnectionUrl() {
        return connectionUrl;
    }

    public void setConnectionUrl(String connectionUrl) {
        this.connectionUrl = connectionUrl;
    }

    public String getConnectionGroup() {
        return connectionGroup;
    }

    public void setConnectionGroup(String connectionGroup) {
        this.connectionGroup = connectionGroup;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDbLoginName() {
        return dbLoginName;
    }

    public void setDbLoginName(String dbLoginName) {
        this.dbLoginName = dbLoginName;
    }

    public String getDbLoginPassword() {
        return dbLoginPassword;
    }

    public void setDbLoginPassword(String dbLoginPassword) {
        this.dbLoginPassword = dbLoginPassword;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getEnvName() {
        return envName;
    }

    public void setEnvName(String envName) {
        this.envName = envName;
    }
}
