package com.aitaskcenter.controller;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.service.AiConfigService;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiConfigControllerTest {
    @Test
    void configResponsesNeverExposeProviderApiKeys() {
        AiConfigService service = mock(AiConfigService.class);
        AiConfigRequest masked = new AiConfigRequest();
        AiProviderConfigItem provider = new AiProviderConfigItem();
        provider.setId("xiaomi-mimo-tts");
        provider.setApiKey(null);
        masked.setProviders(List.of(provider));
        when(service.getProviders()).thenReturn(masked);
        AiConfigController controller = new AiConfigController(service);
        AiConfigRequest request = new AiConfigRequest();

        assertNull(controller.getConfig().data().getProviders().get(0).getApiKey());
        assertNull(controller.saveConfig(request).data().get(0).getApiKey());

        verify(service).save(request);
    }
}
