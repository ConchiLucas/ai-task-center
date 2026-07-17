package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.ExecutionTargetItem;
import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.model.AiConfig;
import com.aitaskcenter.repository.AiConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiConfigServiceExecutionTargetTest {
    @Test
    void mergesProvidersAndCliConfigsIntoSafeExecutionTargetCatalog() {
        AiConfigRepository repository = mock(AiConfigRepository.class);
        AiConfig config = new AiConfig();
        config.setConfigKey("default");
        config.setActive("xiaomi-mimo-tts");
        config.setProviders("""
                {
                  "xiaomi-mimo-tts": {
                    "id": "xiaomi-mimo-tts",
                    "label": "小米 MiMo TTS",
                    "type": "mimo-tts",
                    "base_url": "https://api.xiaomimimo.com/v1",
                    "api_key": "database-secret",
                    "model": "mimo-v2.5-tts",
                    "voice": "Chloe",
                    "capabilities": ["AUDIO_TTS"],
                    "enabled": true
                  }
                }
                """);
        config.setLocalCliConfig("""
                {
                  "active": "codex",
                  "configs": [{
                    "id": "codex",
                    "label": "Codex CLI",
                    "command": "/opt/homebrew/bin/codex",
                    "defaultArgs": ["exec"],
                    "workingDirectory": "/Users/conchi/workforce/python_workforce/ai-task-center",
                    "timeoutSeconds": 300,
                    "enabled": true,
                    "capabilities": ["TEXT_GENERATION", "CODE_EXECUTION"]
                  }]
                }
                """);
        when(repository.findByConfigKey("default")).thenReturn(Optional.of(config));
        AiConfigService service = new AiConfigService(repository, new ObjectMapper());

        List<ExecutionTargetItem> targets = service.getExecutionTargets();

        assertEquals(2, targets.size());
        ExecutionTargetItem provider = targets.stream()
                .filter(item -> "AI_PROVIDER".equals(item.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("xiaomi-mimo-tts", provider.id());
        assertEquals("mimo-tts", provider.protocol());
        assertEquals(List.of("AUDIO_TTS"), provider.capabilities());
        assertFalse(provider.toString().contains("database-secret"));

        ExecutionTargetItem cli = targets.stream()
                .filter(item -> "CLI".equals(item.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("codex", cli.id());
        assertEquals(List.of("TEXT_GENERATION", "CODE_EXECUTION"), cli.capabilities());
    }

    @Test
    void savingMaskedProviderKeepsExistingApiKey() throws Exception {
        AiConfigRepository repository = mock(AiConfigRepository.class);
        AiConfig config = new AiConfig();
        config.setConfigKey("default");
        config.setActive("xiaomi-mimo-tts");
        config.setProviders("""
                {
                  "xiaomi-mimo-tts": {
                    "id": "xiaomi-mimo-tts",
                    "label": "小米 MiMo TTS",
                    "type": "mimo-tts",
                    "base_url": "https://api.xiaomimimo.com/v1",
                    "api_key": "database-secret",
                    "model": "mimo-v2.5-tts",
                    "voice": "Chloe",
                    "capabilities": ["AUDIO_TTS"],
                    "enabled": true
                  }
                }
                """);
        when(repository.findByConfigKey("default")).thenReturn(Optional.of(config));
        ObjectMapper objectMapper = new ObjectMapper();
        AiConfigService service = new AiConfigService(repository, objectMapper);
        AiConfigRequest masked = service.getProviders();

        service.save(masked);

        ArgumentCaptor<AiConfig> configCaptor = ArgumentCaptor.forClass(AiConfig.class);
        verify(repository).save(configCaptor.capture());
        String savedProviders = configCaptor.getValue().getProviders();
        assertEquals(
                "database-secret",
                objectMapper.readTree(savedProviders).path("xiaomi-mimo-tts").path("api_key").asText());
    }

    @Test
    void publicConfigNormalizesLegacyMimoTypeWithoutExposingKey() {
        AiConfigRepository repository = mock(AiConfigRepository.class);
        AiConfig config = new AiConfig();
        config.setConfigKey("default");
        config.setActive("xiaomi-mimo-tts");
        config.setProviders("""
                {
                  "xiaomi-mimo-tts": {
                    "id": "xiaomi-mimo-tts",
                    "label": "小米 MiMo TTS",
                    "type": "openai-compatible",
                    "base_url": "https://api.xiaomimimo.com/v1",
                    "api_key": "database-secret",
                    "model": "mimo-v2.5-tts",
                    "enabled": true
                  }
                }
                """);
        when(repository.findByConfigKey("default")).thenReturn(Optional.of(config));
        AiConfigService service = new AiConfigService(repository, new ObjectMapper());

        AiConfigRequest publicConfig = service.getProviders();

        assertEquals("mimo-tts", publicConfig.getProviders().get(0).getType());
        assertEquals(List.of("AUDIO_TTS"), publicConfig.getProviders().get(0).getCapabilities());
        assertEquals(null, publicConfig.getProviders().get(0).getApiKey());
    }
}
