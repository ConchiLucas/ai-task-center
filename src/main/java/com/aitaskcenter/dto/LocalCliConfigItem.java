package com.aitaskcenter.dto;

import java.util.ArrayList;
import java.util.List;

public class LocalCliConfigItem {
    private boolean enabled = true;
    private String id = "codex";
    private String label = "Codex CLI";
    private String command = "/usr/local/bin/codex";
    private List<String> defaultArgs = new ArrayList<>(List.of("exec"));
    private String workingDirectory = "/Users/conchi/workforce/python_workforce/ai-task-center";
    private Integer timeoutSeconds = 300;
    private boolean active;

    // 方法：isEnabled
    public boolean isEnabled() {
        return enabled;
    }

    // 方法：setEnabled
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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

    // 方法：getCommand
    public String getCommand() {
        return command;
    }

    // 方法：setCommand
    public void setCommand(String command) {
        this.command = command;
    }

    // 方法：getDefaultArgs
    public List<String> getDefaultArgs() {
        return defaultArgs;
    }

    // 方法：setDefaultArgs
    public void setDefaultArgs(List<String> defaultArgs) {
        this.defaultArgs = defaultArgs;
    }

    // 方法：getWorkingDirectory
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    // 方法：setWorkingDirectory
    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    // 方法：getTimeoutSeconds
    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    // 方法：setTimeoutSeconds
    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
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
