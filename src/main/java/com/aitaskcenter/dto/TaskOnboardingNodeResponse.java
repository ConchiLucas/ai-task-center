package com.aitaskcenter.dto;

public class TaskOnboardingNodeResponse {
    private String step;
    private String label;
    private String state;

    public TaskOnboardingNodeResponse() {
    }

    public TaskOnboardingNodeResponse(String step, String label, String state) {
        this.step = step;
        this.label = label;
        this.state = state;
    }

    public String getStep() {
        return step;
    }

    public void setStep(String step) {
        this.step = step;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
