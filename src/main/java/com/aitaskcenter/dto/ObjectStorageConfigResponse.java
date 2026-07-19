package com.aitaskcenter.dto;

public record ObjectStorageConfigResponse(
        Long id,
        String configName,
        String providerType,
        String endpoint,
        String accessKey,
        boolean useSsl,
        String bucketName,
        String basePath,
        boolean enabled,
        boolean isDefault,
        boolean secretConfigured) {
}
