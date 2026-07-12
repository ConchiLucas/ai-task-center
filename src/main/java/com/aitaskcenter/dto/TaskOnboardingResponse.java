package com.aitaskcenter.dto;

import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskOnboardingResponse {
    private TaskConfig task;
    private List<TaskOnboardingNodeResponse> nodes = new ArrayList<>();
    private String currentStep;
    private String currentStatus;
    private String status;
    private String prompt;
    private List<TaskResult> validationResults = new ArrayList<>();
    private TaskRun validationRun;
    private List<TaskResult> validationRunResults = new ArrayList<>();
    private Map<String, Long> counts = new LinkedHashMap<>();
    private List<String> allowedActions = new ArrayList<>();
    private String errorMessage;

    public TaskConfig getTask() {
        return task;
    }

    public void setTask(TaskConfig task) {
        this.task = task;
    }

    public List<TaskOnboardingNodeResponse> getNodes() {
        return nodes;
    }

    public void setNodes(List<TaskOnboardingNodeResponse> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(String currentStep) {
        this.currentStep = currentStep;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public void setCurrentStatus(String currentStatus) {
        this.currentStatus = currentStatus;
        this.status = currentStatus;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.currentStatus = status;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public List<TaskResult> getValidationResults() {
        return validationResults;
    }

    public void setValidationResults(List<TaskResult> validationResults) {
        this.validationResults = validationResults == null ? new ArrayList<>() : new ArrayList<>(validationResults);
    }

    public TaskRun getValidationRun() {
        return validationRun;
    }

    public void setValidationRun(TaskRun validationRun) {
        this.validationRun = validationRun;
    }

    public List<TaskResult> getValidationRunResults() {
        return validationRunResults;
    }

    public void setValidationRunResults(List<TaskResult> validationRunResults) {
        this.validationRunResults = validationRunResults == null
                ? new ArrayList<>()
                : new ArrayList<>(validationRunResults);
    }

    public Map<String, Long> getCounts() {
        return counts;
    }

    public void setCounts(Map<String, Long> counts) {
        this.counts = counts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(counts);
    }

    public List<String> getAllowedActions() {
        return allowedActions;
    }

    public void setAllowedActions(List<String> allowedActions) {
        this.allowedActions = allowedActions == null ? new ArrayList<>() : new ArrayList<>(allowedActions);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
