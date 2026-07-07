package com.aitaskcenter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AiProviderConfigItem {
    private String id;
    private String label;
    private String type;

    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("api_key")
    private String apiKey;

    private String model;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    private boolean active;

    // 方法：getId
    public String getId() {
        return id;
    }

    // 方法：setId
    public void setId(String id) {
        this.id = id;
    }

    // 方法：getLabel
    public String getLabel() {
        return label;
    }

    // 方法：setLabel
    public void setLabel(String label) {
        this.label = label;
    }

    // 方法：getType
    public String getType() {
        return type;
    }

    // 方法：setType
    public void setType(String type) {
        this.type = type;
    }

    // 方法：getBaseUrl
    public String getBaseUrl() {
        return baseUrl;
    }

    // 方法：setBaseUrl
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    // 方法：getApiKey
    public String getApiKey() {
        return apiKey;
    }

    // 方法：setApiKey
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    // 方法：getModel
    public String getModel() {
        return model;
    }

    // 方法：setModel
    public void setModel(String model) {
        this.model = model;
    }

    // 方法：getMaxTokens
    public Integer getMaxTokens() {
        return maxTokens;
    }

    // 方法：setMaxTokens
    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    // 方法：isActive
    public boolean isActive() {
        return active;
    }

    // 方法：setActive
    public void setActive(boolean active) {
        this.active = active;
    }
}
