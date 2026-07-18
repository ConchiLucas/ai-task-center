package com.aitaskcenter.dto;

public record TaskHandlerDescriptor(
        String handlerKey,
        String requiredCapability,
        boolean supportsResultGeneration,
        boolean supportsSingleValidation,
        boolean supportsBatchBuild,
        boolean supportsBatchExecution) {}
