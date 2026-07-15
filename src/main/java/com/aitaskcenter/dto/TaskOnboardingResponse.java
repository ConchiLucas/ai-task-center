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
    private String prompt;
    private List<TaskOnboardingResultSummary> validationResults = new ArrayList<>();
    private TaskOnboardingRunSummary validationRun;
    private List<TaskOnboardingResultSummary> validationRunResults = new ArrayList<>();
    private Map<String, Long> counts = new LinkedHashMap<>();
    private List<String> allowedActions = new ArrayList<>();
    private String errorMessage;

    public TaskOnboardingTaskSummary getTask() { return task; }
    public void setTask(TaskOnboardingTaskSummary task) { this.task = task; }
    public List<TaskOnboardingNodeResponse> getNodes() { return nodes; }
    public void setNodes(List<TaskOnboardingNodeResponse> nodes) { this.nodes = nodes; }
    public String getCurrentStep() { return currentStep; }
    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }
    public String getCurrentStatus() { return currentStatus; }
    public void setCurrentStatus(String currentStatus) { this.currentStatus = currentStatus; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public List<TaskOnboardingResultSummary> getValidationResults() { return validationResults; }
    public void setValidationResults(List<TaskOnboardingResultSummary> validationResults) { this.validationResults = validationResults; }
    public TaskOnboardingRunSummary getValidationRun() { return validationRun; }
    public void setValidationRun(TaskOnboardingRunSummary validationRun) { this.validationRun = validationRun; }
    public List<TaskOnboardingResultSummary> getValidationRunResults() { return validationRunResults; }
    public void setValidationRunResults(List<TaskOnboardingResultSummary> validationRunResults) { this.validationRunResults = validationRunResults; }
    public Map<String, Long> getCounts() { return counts; }
    public void setCounts(Map<String, Long> counts) { this.counts = counts; }
    public List<String> getAllowedActions() { return allowedActions; }
    public void setAllowedActions(List<String> allowedActions) { this.allowedActions = allowedActions; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
