package com.aitaskcenter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class PythonWorkerConfig {
    // 方法：pythonWorkerRestClient
    @Bean
    public RestClient pythonWorkerRestClient(@Value("${python-worker.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}
