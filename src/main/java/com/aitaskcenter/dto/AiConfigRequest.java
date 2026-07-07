package com.aitaskcenter.dto;

import java.util.ArrayList;
import java.util.List;

public class AiConfigRequest {
    private String active;
    private List<AiProviderConfigItem> providers = new ArrayList<>();

    // 方法：getActive
    public String getActive() {
        return active;
    }

    // 方法：setActive
    public void setActive(String active) {
        this.active = active;
    }

    // 方法：getProviders
    public List<AiProviderConfigItem> getProviders() {
        return providers;
    }

    // 方法：setProviders
    public void setProviders(List<AiProviderConfigItem> providers) {
        this.providers = providers;
    }
}
