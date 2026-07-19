package com.aitaskcenter.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aitaskcenter.model.ObjectStorageConfig;
import com.aitaskcenter.dto.ObjectStorageConfigRequest;
import com.aitaskcenter.repository.ObjectStorageConfigRepository;
import com.aitaskcenter.service.ObjectStorageConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectStorageConfigControllerTest {
    @Test
    void requestAndResponseUseIsDefaultJsonProperty() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        ObjectStorageConfigRequest request = mapper.readValue("{\"isDefault\":true}", ObjectStorageConfigRequest.class);

        assertTrue(request.isDefault());
    }

    @Test
    void listResponseExposesSecretStateWithoutSecretValue() throws Exception {
        ObjectStorageConfigRepository repository = mock(ObjectStorageConfigRepository.class);
        ObjectStorageConfig config = new ObjectStorageConfig();
        config.setId(7L);
        config.setConfigName("本地 MinIO");
        config.setProviderType("MINIO");
        config.setEndpoint("127.0.0.1:19100");
        config.setAccessKey("minio-access");
        config.setSecretKey("minio-secret");
        config.setBucketName("ai-file-navigation");
        config.setBasePath("word_clean_tts");
        config.setEnabled(true);
        config.setDefault(true);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(config));
        ObjectStorageConfigService service = new ObjectStorageConfigService(repository);
        ObjectStorageConfigController controller = new ObjectStorageConfigController(service);

        String json = new ObjectMapper().writeValueAsString(controller.list());

        assertTrue(json.contains("\"secretConfigured\":true"));
        assertTrue(json.contains("\"isDefault\":true"));
        assertFalse(json.toLowerCase().contains("secretkey"));
        assertFalse(json.contains("minio-secret"));
    }
}
