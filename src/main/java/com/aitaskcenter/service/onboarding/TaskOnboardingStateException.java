package com.aitaskcenter.service.onboarding;

public class TaskOnboardingStateException extends IllegalStateException {
    public TaskOnboardingStateException(String message) {
        super(message);
    }

    public TaskOnboardingStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
