package com.aitaskcenter.service;

import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.LocalCliConfigItem;
import com.aitaskcenter.dto.LocalCliConfigRequest;
import com.aitaskcenter.model.AiConfig;
import com.aitaskcenter.repository.AiConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AiConfigService {
    private static final String DEFAULT_KEY = "default";
    private static final String OPENAI_COMPATIBLE = "openai-compatible";
    private static final String ANTHROPIC_COMPATIBLE = "anthropic-compatible";

    private final AiConfigRepository repository;
    private final ObjectMapper objectMapper;

    // 方法：AiConfigService
    public AiConfigService(AiConfigRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    // 方法：getConfig
    public AiConfigRequest getConfig() {
        return repository.findByConfigKey(DEFAULT_KEY)
                .map(this::toRequest)
                .orElseGet(AiConfigRequest::new);
    }

    // 方法：getProviders
    public AiConfigRequest getProviders() {
        AiConfigRequest request = getConfig();
        request.getProviders().forEach(provider -> provider.setApiKey(null));
        return request;
    }

    // 方法：getLocalCliConfig
    public LocalCliConfigRequest getLocalCliConfig() {
        return repository.findByConfigKey(DEFAULT_KEY)
                .map(this::toLocalCliConfig)
                .orElseGet(this::defaultLocalCliConfig);
    }

    @Transactional
    // 方法：save
    public List<AiProviderConfigItem> save(AiConfigRequest request) {
        AiConfigRequest normalized = normalize(request);
        String providersJson = toJson(toProviderMap(normalized.getProviders()));
        AiConfig config = repository.findByConfigKey(DEFAULT_KEY).orElseGet(AiConfig::new);
        config.setConfigKey(DEFAULT_KEY);
        config.setActive(normalized.getActive());
        config.setProviders(providersJson);
        repository.save(config);
        return normalized.getProviders();
    }

    @Transactional
    // 方法：saveActive
    public List<AiProviderConfigItem> saveActive(String active) {
        AiConfigRequest current = getConfig();
        if (!StringUtils.hasText(active)) {
            throw new IllegalArgumentException("请选择默认 AI 配置");
        }
        String trimmed = active.trim();
        boolean exists = current.getProviders().stream().anyMatch(provider -> trimmed.equals(provider.getId()));
        if (!exists) {
            throw new IllegalArgumentException("默认 AI 配置「" + trimmed + "」不存在");
        }
        current.setActive(trimmed);
        return save(current);
    }

    @Transactional
    // 方法：saveLocalCliConfig
    public LocalCliConfigRequest saveLocalCliConfig(LocalCliConfigRequest request) {
        LocalCliConfigRequest normalized = normalizeLocalCliConfigRequest(request);
        AiConfig config = repository.findByConfigKey(DEFAULT_KEY).orElseGet(AiConfig::new);
        config.setConfigKey(DEFAULT_KEY);
        if (!StringUtils.hasText(config.getProviders())) {
            config.setProviders("{}");
        }
        config.setLocalCliConfig(toJson(normalized));
        repository.save(config);
        return normalized;
    }

    // 方法：toRequest
    private AiConfigRequest toRequest(AiConfig config) {
        Map<String, AiProviderConfigItem> map = fromJson(config.getProviders());
        List<AiProviderConfigItem> providers = new ArrayList<>(map.values());
        providers.sort(Comparator.comparing(AiProviderConfigItem::getId));
        String active = firstExistingActive(config.getActive(), providers);
        providers.forEach(provider -> provider.setActive(provider.getId().equals(active)));
        AiConfigRequest request = new AiConfigRequest();
        request.setActive(active);
        request.setProviders(providers);
        return request;
    }

    // 方法：toLocalCliConfig
    private LocalCliConfigRequest toLocalCliConfig(AiConfig config) {
        if (!StringUtils.hasText(config.getLocalCliConfig())) {
            return defaultLocalCliConfig();
        }
        try {
            return normalizeLocalCliConfigRequest(objectMapper.readValue(config.getLocalCliConfig(), LocalCliConfigRequest.class));
        } catch (Exception ex) {
            try {
                LocalCliConfigItem legacy = objectMapper.readValue(config.getLocalCliConfig(), LocalCliConfigItem.class);
                LocalCliConfigRequest migrated = new LocalCliConfigRequest();
                migrated.setActive(defaultText(legacy.getId(), "codex"));
                migrated.setConfigs(List.of(legacy));
                return normalizeLocalCliConfigRequest(migrated);
            } catch (Exception legacyEx) {
                throw new IllegalArgumentException("本地 CLI 配置 JSON 解析失败: " + ex.getMessage());
            }
        }
    }

    // 方法：normalize
    private AiConfigRequest normalize(AiConfigRequest request) {
        if (request == null || request.getProviders() == null || request.getProviders().isEmpty()) {
            throw new IllegalArgumentException("请至少保留一个 AI 配置");
        }
        Map<String, AiProviderConfigItem> byId = new LinkedHashMap<>();
        for (AiProviderConfigItem item : request.getProviders()) {
            String id = require(item.getId(), "请填写 AI 配置 ID");
            if (byId.containsKey(id)) {
                throw new IllegalArgumentException("AI 配置 ID「" + id + "」重复");
            }
            String type = defaultText(item.getType(), OPENAI_COMPATIBLE);
            if (!OPENAI_COMPATIBLE.equals(type) && !ANTHROPIC_COMPATIBLE.equals(type)) {
                throw new IllegalArgumentException("AI 配置「" + id + "」的类型不支持");
            }
            AiProviderConfigItem normalized = new AiProviderConfigItem();
            normalized.setId(id);
            normalized.setLabel(clean(item.getLabel()));
            normalized.setType(type);
            normalized.setBaseUrl(trimTrailingSlash(require(item.getBaseUrl(), "请填写 AI 配置「" + id + "」的 Base URL")));
            normalized.setApiKey(clean(item.getApiKey()));
            normalized.setModel(require(item.getModel(), "请填写 AI 配置「" + id + "」的模型名称"));
            normalized.setMaxTokens(item.getMaxTokens() == null || item.getMaxTokens() <= 0 ? 4096 : item.getMaxTokens());
            byId.put(id, normalized);
        }
        String active = clean(request.getActive());
        if (!StringUtils.hasText(active)) {
            active = byId.keySet().iterator().next();
        }
        if (!byId.containsKey(active)) {
            throw new IllegalArgumentException("默认 AI 配置「" + active + "」不存在");
        }
        AiConfigRequest normalized = new AiConfigRequest();
        normalized.setActive(active);
        List<AiProviderConfigItem> providers = new ArrayList<>(byId.values());
        String activeId = active;
        providers.forEach(provider -> provider.setActive(provider.getId().equals(activeId)));
        normalized.setProviders(providers);
        return normalized;
    }

    // 方法：toProviderMap
    private Map<String, AiProviderConfigItem> toProviderMap(List<AiProviderConfigItem> providers) {
        Map<String, AiProviderConfigItem> map = new LinkedHashMap<>();
        for (AiProviderConfigItem provider : providers) {
            map.put(provider.getId(), provider);
        }
        return map;
    }

    // 方法：normalizeLocalCliConfigRequest
    private LocalCliConfigRequest normalizeLocalCliConfigRequest(LocalCliConfigRequest request) {
        LocalCliConfigRequest source = request == null ? defaultLocalCliConfig() : request;
        List<LocalCliConfigItem> sourceConfigs = source.getConfigs() == null ? new ArrayList<>() : source.getConfigs();
        if (sourceConfigs.isEmpty()) {
            sourceConfigs = defaultLocalCliConfig().getConfigs();
        }

        Map<String, LocalCliConfigItem> byId = new LinkedHashMap<>();
        for (LocalCliConfigItem item : sourceConfigs) {
            LocalCliConfigItem normalized = normalizeLocalCliConfigItem(item);
            if (byId.containsKey(normalized.getId())) {
                throw new IllegalArgumentException("CLI 配置 ID「" + normalized.getId() + "」重复");
            }
            byId.put(normalized.getId(), normalized);
        }

        String active = clean(source.getActive());
        if (!StringUtils.hasText(active) || !byId.containsKey(active)) {
            active = byId.keySet().iterator().next();
        }

        String activeId = active;
        byId.values().forEach(item -> item.setActive(item.getId().equals(activeId)));

        LocalCliConfigRequest normalized = new LocalCliConfigRequest();
        normalized.setActive(active);
        normalized.setConfigs(new ArrayList<>(byId.values()));
        return normalized;
    }

    // 方法：normalizeLocalCliConfigItem
    private LocalCliConfigItem normalizeLocalCliConfigItem(LocalCliConfigItem source) {
        LocalCliConfigItem item = source == null ? new LocalCliConfigItem() : source;
        LocalCliConfigItem normalized = new LocalCliConfigItem();
        normalized.setEnabled(item.isEnabled());
        normalized.setId(require(defaultText(item.getId(), "codex"), "请填写 CLI 配置 ID"));
        normalized.setLabel(defaultText(item.getLabel(), normalized.getId()));
        normalized.setCommand(require(item.getCommand(), "请填写 CLI 命令路径"));
        normalized.setDefaultArgs(item.getDefaultArgs() == null ? new ArrayList<>() : item.getDefaultArgs().stream()
                .map(AiConfigService::clean)
                .filter(StringUtils::hasText)
                .toList());
        boolean codex = "codex".equalsIgnoreCase(normalized.getId())
                || normalized.getCommand().toLowerCase().contains("codex");
        normalized.setModel(codex ? defaultText(item.getModel(), "gpt-5.6-sol") : clean(item.getModel()));
        normalized.setReasoningEffort(codex ? defaultText(item.getReasoningEffort(), "low") : clean(item.getReasoningEffort()));
        normalized.setWorkingDirectory(defaultText(item.getWorkingDirectory(), "/Users/conchi/workforce/python_workforce/ai-task-center"));
        normalized.setTimeoutSeconds(item.getTimeoutSeconds() == null || item.getTimeoutSeconds() <= 0 ? 300 : item.getTimeoutSeconds());
        return normalized;
    }

    // 方法：defaultLocalCliConfig
    private LocalCliConfigRequest defaultLocalCliConfig() {
        LocalCliConfigRequest request = new LocalCliConfigRequest();
        LocalCliConfigItem codex = new LocalCliConfigItem();
        codex.setActive(true);
        request.setActive("codex");
        request.setConfigs(List.of(codex));
        return request;
    }

    // 方法：fromJson
    private Map<String, AiProviderConfigItem> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, AiProviderConfigItem>>() {
            });
        } catch (Exception ex) {
            throw new IllegalArgumentException("AI 配置 JSON 解析失败: " + ex.getMessage());
        }
    }

    // 方法：toJson
    private String toJson(Map<String, AiProviderConfigItem> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            throw new IllegalArgumentException("AI 配置 JSON 序列化失败: " + ex.getMessage());
        }
    }

    // 方法：toJson
    private String toJson(LocalCliConfigRequest config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception ex) {
            throw new IllegalArgumentException("本地 CLI 配置 JSON 序列化失败: " + ex.getMessage());
        }
    }

    // 方法：firstExistingActive
    private static String firstExistingActive(String active, List<AiProviderConfigItem> providers) {
        String cleaned = clean(active);
        if (StringUtils.hasText(cleaned)) {
            for (AiProviderConfigItem provider : providers) {
                if (cleaned.equals(provider.getId())) {
                    return cleaned;
                }
            }
        }
        return providers.isEmpty() ? "" : providers.get(0).getId();
    }

    // 方法：require
    private static String require(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    // 方法：clean
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    // 方法：defaultText
    private static String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    // 方法：trimTrailingSlash
    private static String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
