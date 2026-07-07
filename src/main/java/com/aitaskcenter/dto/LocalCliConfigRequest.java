package com.aitaskcenter.dto;

import java.util.ArrayList;
import java.util.List;

public class LocalCliConfigRequest {
    private String active = "codex";
    private List<LocalCliConfigItem> configs = new ArrayList<>();

    // 方法：getActive
    public String getActive() {
        return active;
    }

    // 方法：setActive
    public void setActive(String active) {
        this.active = active;
    }

    // 方法：getConfigs
    public List<LocalCliConfigItem> getConfigs() {
        return configs;
    }

    // 方法：setConfigs
    public void setConfigs(List<LocalCliConfigItem> configs) {
        this.configs = configs;
    }
}
