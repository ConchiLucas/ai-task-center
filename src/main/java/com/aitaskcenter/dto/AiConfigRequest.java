package com.aitaskcenter.dto;

import java.util.ArrayList;
import java.util.List;

public class AiConfigRequest {
    private String active;
    private List<AiProviderConfigItem> providers = new ArrayList<>();

    public String getActive() {
        return active;
    }

    public void setActive(String active) {
        this.active = active;
    }

    public List<AiProviderConfigItem> getProviders() {
        return providers;
    }

    public void setProviders(List<AiProviderConfigItem> providers) {
        this.providers = providers;
    }
}
