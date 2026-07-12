package com.aitaskcenter.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskOnboardingResponse {
    private TaskOnboardingTaskSummary task;
    private List<TaskOnboardingNodeResponse> nodes = new ArrayList<>();
    private String currentStep;
    private String currentStatus;
    private String status;
    private String prompt;
    private List<TaskOnboardingResultSummary> validationResults = new ArrayList<>();
    private TaskOnboardingRunSummary validationRun;
    private List<TaskOnboardingResultSummary> validationRunResults = new ArrayList<>();
    private Map<String, Long> counts = new LinkedHashMap<>();
    private List<String> allowedActions = new ArrayList<>();
    private String errorMessage;

    public TaskOnboardingTaskSummary getTask() {
        return task;
    }

    public void setTask(TaskOnboardingTaskSummary task) {
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

    public List<TaskOnboardingResultSummary> getValidationResults() {
        return validationResults;
    }

    public void setValidationResults(List<TaskOnboardingResultSummary> validationResults) {
        this.validationResults = validationResults == null ? new ArrayList<>() : new ArrayList<>(validationResults);
    }

    public TaskOnboardingRunSummary getValidationRun() {
        return validationRun;
    }

    public void setValidationRun(TaskOnboardingRunSummary validationRun) {
        this.validationRun = validationRun;
    }

    public List<TaskOnboardingResultSummary> getValidationRunResults() {
        return validationRunResults;
    }

    public void setValidationRunResults(List<TaskOnboardingResultSummary> validationRunResults) {
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
