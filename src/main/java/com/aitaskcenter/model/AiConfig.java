package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_ai_config")
public class AiConfig extends BaseEntity {
    @Column(nullable = false, unique = true)
    // 字段：AI 配置唯一键
    private String configKey;

    // 字段：当前默认启用的 AI 配置 ID
    private String active;

    @Column(nullable = false, columnDefinition = "text")
    // 字段：AI 提供商配置 JSON
    private String providers;

    @Column(columnDefinition = "text")
    // 字段：本地 CLI 配置 JSON
    private String localCliConfig;

    // 方法：getConfigKey
    public String getConfigKey() {
        return configKey;
    }

    // 方法：setConfigKey
    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    // 方法：getActive
    public String getActive() {
        return active;
    }

    // 方法：setActive
    public void setActive(String active) {
        this.active = active;
    }

    // 方法：getProviders
    public String getProviders() {
        return providers;
    }

    // 方法：setProviders
    public void setProviders(String providers) {
        this.providers = providers;
    }

    // 方法：getLocalCliConfig
    public String getLocalCliConfig() {
        return localCliConfig;
    }

    // 方法：setLocalCliConfig
    public void setLocalCliConfig(String localCliConfig) {
        this.localCliConfig = localCliConfig;
    }
}
