package com.aitaskcenter.model;

import java.util.List;
import java.util.Locale;

public final class TaskRecordType {
    public static final String VALIDATION_CURRENT = "VALIDATION_CURRENT";
    public static final String VALIDATION_HISTORY = "VALIDATION_HISTORY";
    public static final String FORMAL = "FORMAL";
    public static final String ALL = "ALL";

    private TaskRecordType() {
    }

    public static String normalizeFilter(String value) {
        String normalized = value == null || value.isBlank()
                ? FORMAL
                : value.trim().toUpperCase(Locale.ROOT);
        if (ALL.equals(normalized)) {
            return null;
        }
        if (!List.of(VALIDATION_CURRENT, VALIDATION_HISTORY, FORMAL).contains(normalized)) {
            throw new IllegalArgumentException("数据类型筛选值无效");
        }
        return normalized;
    }
}
