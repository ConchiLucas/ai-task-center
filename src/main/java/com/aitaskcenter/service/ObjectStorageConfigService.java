package com.aitaskcenter.service;

import com.aitaskcenter.dto.ObjectStorageConfigRequest;
import com.aitaskcenter.dto.ObjectStorageConfigResponse;
import com.aitaskcenter.model.ObjectStorageConfig;
import com.aitaskcenter.repository.ObjectStorageConfigRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ObjectStorageConfigService {
    private final ObjectStorageConfigRepository repository;

    public ObjectStorageConfigService(ObjectStorageConfigRepository repository) {
        this.repository = repository;
    }

    public List<ObjectStorageConfigResponse> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(ObjectStorageConfigService::response).toList();
    }

    @Transactional
    public ObjectStorageConfigResponse create(ObjectStorageConfigRequest request) {
        ObjectStorageConfig target = new ObjectStorageConfig();
        copyAndValidate(request, target, true);
        replaceDefaultIfNeeded(target);
        return response(repository.save(target));
    }

    @Transactional
    public ObjectStorageConfigResponse update(Long id, ObjectStorageConfigRequest request) {
        ObjectStorageConfig target = requireConfig(id);
        copyAndValidate(request, target, false);
        replaceDefaultIfNeeded(target);
        return response(repository.save(target));
    }

    @Transactional
    public ObjectStorageConfigResponse setDefault(Long id) {
        ObjectStorageConfig target = requireConfig(id);
        if (!target.isEnabled()) {
            throw new IllegalArgumentException("默认对象存储配置必须启用");
        }
        target.setDefault(true);
        replaceDefaultIfNeeded(target);
        return response(repository.save(target));
    }

    @Transactional
    public void delete(Long id) {
        ObjectStorageConfig target = requireConfig(id);
        if (target.isDefault()) {
            throw new IllegalArgumentException("默认对象存储配置不能删除");
        }
        repository.delete(target);
    }

    private ObjectStorageConfig requireConfig(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("缺少对象存储配置 ID");
        }
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("对象存储配置不存在"));
    }

    private void replaceDefaultIfNeeded(ObjectStorageConfig target) {
        if (!target.isDefault()) {
            return;
        }
        repository.findByIsDefaultTrue()
                .filter(previous -> target.getId() == null || !target.getId().equals(previous.getId()))
                .ifPresent(previous -> {
                    previous.setDefault(false);
                    repository.save(previous);
                });
    }

    private static void copyAndValidate(
            ObjectStorageConfigRequest request,
            ObjectStorageConfig target,
            boolean creating) {
        if (request == null) {
            throw new IllegalArgumentException("对象存储配置不能为空");
        }
        target.setConfigName(require(request.getConfigName(), "请填写配置名称"));
        String providerType = require(request.getProviderType(), "请选择存储类型").toUpperCase(Locale.ROOT);
        if (!"MINIO".equals(providerType)) {
            throw new IllegalArgumentException("当前仅支持 MINIO 对象存储");
        }
        target.setProviderType(providerType);
        target.setEndpoint(require(request.getEndpoint(), "请填写 Endpoint"));
        target.setAccessKey(require(request.getAccessKey(), "请填写 Access Key"));
        if (StringUtils.hasText(request.getSecretKey())) {
            target.setSecretKey(request.getSecretKey().trim());
        } else if (creating || !StringUtils.hasText(target.getSecretKey())) {
            throw new IllegalArgumentException("请填写 Secret Key");
        }
        target.setUseSsl(request.isUseSsl());
        target.setBucketName(cleanPath(require(request.getBucketName(), "请填写 Bucket")));
        target.setBasePath(cleanPath(require(request.getBasePath(), "请填写基础路径")));
        target.setEnabled(request.isEnabled());
        target.setDefault(request.isDefault());
        if (target.isDefault() && !target.isEnabled()) {
            throw new IllegalArgumentException("默认对象存储配置必须启用");
        }
    }

    private static ObjectStorageConfigResponse response(ObjectStorageConfig config) {
        return new ObjectStorageConfigResponse(
                config.getId(),
                config.getConfigName(),
                config.getProviderType(),
                config.getEndpoint(),
                config.getAccessKey(),
                config.isUseSsl(),
                config.getBucketName(),
                config.getBasePath(),
                config.isEnabled(),
                config.isDefault(),
                StringUtils.hasText(config.getSecretKey()));
    }

    private static String require(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String cleanPath(String value) {
        return value.replaceAll("^/+|/+$", "");
    }
}
