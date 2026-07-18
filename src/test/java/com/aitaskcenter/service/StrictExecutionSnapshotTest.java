package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StrictExecutionSnapshotTest {
    private final TaskExecutionTargetResolver resolver = new TaskExecutionTargetResolver();

    @Test
    void completeSnapshotIsNormalized() {
        var target = resolver.require(" task_config_42 ", "cli", " codex ");

        assertEquals("task_config_42", target.handlerKey());
        assertEquals("CLI", target.executorType());
        assertEquals("codex", target.executorId());
    }

    @Test
    void incompleteSnapshotIsRejectedInsteadOfInferred() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.require("", "CLI", "codex"));
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.require("task_config_42", "", "codex"));
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.require("task_config_42", "CLI", ""));
    }
}
