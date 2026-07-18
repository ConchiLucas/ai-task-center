package com.aitaskcenter.service;

import com.aitaskcenter.dto.PythonWorkerStartRequest;
import com.aitaskcenter.dto.TaskHandlerDescriptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    // 方法：generateTaskResults
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateTaskResults(Long taskConfigId, boolean overwrite) {
        return generateTaskResults(taskConfigId, overwrite, "FORMAL", null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateTaskResults(
            Long taskConfigId, boolean overwrite, String recordType, Integer limit) {
        String uri = baseUrl
                + "/api/result-generation/from-task-config-simple?taskConfigId=" + taskConfigId
                + "&overwrite=" + overwrite
                + "&recordType=" + encode(recordType)
                + (limit == null ? "" : "&limit=" + limit);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(uri))
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

    // 方法：processTaskResult
    @SuppressWarnings("unchecked")
    public Map<String, Object> processTaskResult(Long taskResultId, String cliId) {
        String uri = baseUrl
                + "/api/task-result/process-simple?taskResultId=" + taskResultId
                + "&cliId=" + encode(cliId);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(uri))
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

    // 方法：processTaskResults
    @SuppressWarnings("unchecked")
    public Map<String, Object> processTaskResults(List<Long> taskResultIds, String cliId, Integer workerCount) {
        String ids = taskResultIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        String uri = baseUrl
                + "/api/task-result/process-batch-simple?taskResultIds=" + encode(ids)
                + "&cliId=" + encode(cliId)
                + "&workerCount=" + (workerCount == null ? 4 : workerCount);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(uri))
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

    // 方法：processTaskRunBatch
    @SuppressWarnings("unchecked")
    public Map<String, Object> processTaskRunBatch(Long taskRunId, String cliId) {
        String uri = baseUrl
                + "/api/task-run/process-batch-json-simple?taskRunId=" + taskRunId
                + "&cliId=" + encode(cliId);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(uri))
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

    public TaskHandlerDescriptor getTaskHandler(String handlerKey) {
        String uri = baseUrl + "/api/task-handlers/" + encode(handlerKey);
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalArgumentException("Python Worker 任务处理器检查失败: " + response.body());
            }
            return objectMapper.readValue(response.body(), TaskHandlerDescriptor.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Python Worker 任务处理器响应解析失败: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Python Worker 任务处理器检查被中断");
        }
    }

    // 方法：encode
    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
