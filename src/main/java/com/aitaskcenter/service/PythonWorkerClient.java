package com.aitaskcenter.service;

import com.aitaskcenter.dto.PythonWorkerStartRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PythonWorkerClient {
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // 方法：PythonWorkerClient
    public PythonWorkerClient(@Value("${python-worker.base-url}") String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
    }

    // 方法：startExecution
    @SuppressWarnings("unchecked")
    public Map<String, Object> startExecution(PythonWorkerStartRequest request) {
        String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Python Worker 请求序列化失败: " + ex.getMessage());
        }
        String taskRunIds = request.getTaskRuns().stream()
                .map(item -> String.valueOf(item.getId()))
                .collect(Collectors.joining(","));
        String uri = baseUrl
                + "/api/execution/start-simple?cliId=" + encode(request.getCliId())
                + "&executionMode=" + encode(request.getExecutionMode())
                + "&workerCount=" + request.getWorkerCount()
                + "&taskRunIds=" + encode(taskRunIds);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalArgumentException("Python Worker 调用失败: " + response.body());
            }
            return objectMapper.readValue(response.body(), Map.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Python Worker 响应解析失败: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Python Worker 调用被中断");
        }
    }

    // 方法：encode
    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
