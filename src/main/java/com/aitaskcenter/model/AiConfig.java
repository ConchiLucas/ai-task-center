package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_ai_config")
public class AiConfig extends BaseEntity {
    @Column(nullable = false, unique = true)
    private String configKey;

    private String active;

    @Column(nullable = false, columnDefinition = "text")
    private String providers;

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getActive() {
        return active;
    }

    public void setActive(String active) {
        this.active = active;
    }

    public String getProviders() {
        return providers;
    }

    public void setProviders(String providers) {
        this.providers = providers;
    }
}
