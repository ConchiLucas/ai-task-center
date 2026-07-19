package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.ObjectStorageConfigRequest;
import com.aitaskcenter.dto.ObjectStorageConfigResponse;
import com.aitaskcenter.model.ObjectStorageConfig;
import com.aitaskcenter.repository.ObjectStorageConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ObjectStorageConfigServiceTest {
    @Test
    void createNormalizesMinioAndMasksSecret() {
        ObjectStorageConfigRepository repository = mock(ObjectStorageConfigRepository.class);
        when(repository.findByIsDefaultTrue()).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ObjectStorageConfig saved = invocation.getArgument(0);
            saved.setId(7L);
            return saved;
        });
        ObjectStorageConfigService service = new ObjectStorageConfigService(repository);

        ObjectStorageConfigResponse response = service.create(validRequest());

        assertEquals(7L, response.id());
        assertEquals("MINIO", response.providerType());
        assertEquals("127.0.0.1:19100", response.endpoint());
        assertEquals("word_clean_tts", response.basePath());
        assertTrue(response.secretConfigured());
        assertTrue(response.isDefault());
        assertFalse(response.toString().contains("minio-secret"));
    }

    @Test
    void blankSecretOnUpdatePreservesStoredSecret() {
        ObjectStorageConfigRepository repository = mock(ObjectStorageConfigRepository.class);
        ObjectStorageConfig existing = storedConfig();
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.findByIsDefaultTrue()).thenReturn(Optional.of(existing));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        ObjectStorageConfigService service = new ObjectStorageConfigService(repository);
        ObjectStorageConfigRequest request = validRequest();
        request.setSecretKey("   ");

        service.update(7L, request);

        ArgumentCaptor<ObjectStorageConfig> captor = ArgumentCaptor.forClass(ObjectStorageConfig.class);
        verify(repository).save(captor.capture());
        assertEquals("stored-secret", captor.getValue().getSecretKey());
    }

    @Test
    void selectingNewDefaultClearsPreviousDefault() {
        ObjectStorageConfigRepository repository = mock(ObjectStorageConfigRepository.class);
        ObjectStorageConfig previous = storedConfig();
        previous.setId(3L);
        when(repository.findByIsDefaultTrue()).thenReturn(Optional.of(previous));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        ObjectStorageConfigService service = new ObjectStorageConfigService(repository);

        service.create(validRequest());

        assertFalse(previous.isDefault());
        verify(repository).save(previous);
    }

    @Test
    void rejectsDisabledDefaultAndMissingCreateSecret() {
        ObjectStorageConfigRepository repository = mock(ObjectStorageConfigRepository.class);
        ObjectStorageConfigService service = new ObjectStorageConfigService(repository);
        ObjectStorageConfigRequest disabledDefault = validRequest();
        disabledDefault.setEnabled(false);
        assertEquals(
                "默认对象存储配置必须启用",
                assertThrows(IllegalArgumentException.class, () -> service.create(disabledDefault)).getMessage());

        ObjectStorageConfigRequest missingSecret = validRequest();
        missingSecret.setSecretKey("");
        assertEquals(
                "请填写 Secret Key",
                assertThrows(IllegalArgumentException.class, () -> service.create(missingSecret)).getMessage());
    }

    private static ObjectStorageConfigRequest validRequest() {
        ObjectStorageConfigRequest request = new ObjectStorageConfigRequest();
        request.setConfigName("本地 MinIO");
        request.setProviderType("minio");
        request.setEndpoint(" 127.0.0.1:19100 ");
        request.setAccessKey("minio-access");
        request.setSecretKey("minio-secret");
        request.setUseSsl(false);
        request.setBucketName("ai-file-navigation");
        request.setBasePath("/word_clean_tts/");
        request.setEnabled(true);
        request.setDefault(true);
        return request;
    }

    private static ObjectStorageConfig storedConfig() {
        ObjectStorageConfig config = new ObjectStorageConfig();
        config.setId(7L);
        config.setConfigName("本地 MinIO");
        config.setProviderType("MINIO");
        config.setEndpoint("127.0.0.1:19100");
        config.setAccessKey("minio-access");
        config.setSecretKey("stored-secret");
        config.setUseSsl(false);
        config.setBucketName("ai-file-navigation");
        config.setBasePath("word_clean_tts");
        config.setEnabled(true);
        config.setDefault(true);
        return config;
    }
}
