package com.aitaskcenter.dto;

import java.util.List;

public record ExecutionTargetItem(
        String type,
        String id,
        String label,
        String protocol,
        List<String> capabilities,
        boolean enabled) {}
