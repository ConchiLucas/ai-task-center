package com.aitaskcenter.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TaskRecordTypeTest {
    @Test
    void defaultsToFormalAndSupportsAllValidationFilters() {
        assertEquals(TaskRecordType.FORMAL, TaskRecordType.normalizeFilter(null));
        assertEquals(TaskRecordType.VALIDATION_CURRENT, TaskRecordType.normalizeFilter("validation_current"));
        assertEquals(TaskRecordType.VALIDATION_HISTORY, TaskRecordType.normalizeFilter(" VALIDATION_HISTORY "));
        assertNull(TaskRecordType.normalizeFilter("ALL"));
    }

    @Test
    void rejectsUnknownRecordTypeFilter() {
        assertThrows(IllegalArgumentException.class, () -> TaskRecordType.normalizeFilter("UNKNOWN"));
    }
}
