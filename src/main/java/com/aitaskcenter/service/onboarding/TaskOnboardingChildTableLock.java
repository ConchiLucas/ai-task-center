package com.aitaskcenter.service.onboarding;

public interface TaskOnboardingChildTableLock {
    void lockForCallbackValidation();

    default void lockForCleanup() {
        lockForCallbackValidation();
    }
}
