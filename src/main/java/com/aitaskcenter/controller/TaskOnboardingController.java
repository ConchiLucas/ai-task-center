package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.GenerateTaskRunBatchRequest;
import com.aitaskcenter.dto.TaskOnboardingReportRequest;
import com.aitaskcenter.dto.TaskOnboardingResponse;
import com.aitaskcenter.service.onboarding.TaskOnboardingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/task/{id}/onboarding")
public class TaskOnboardingController {
    private final TaskOnboardingService service;

    public TaskOnboardingController(TaskOnboardingService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<TaskOnboardingResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/report")
    public ApiResponse<TaskOnboardingResponse> report(
            @PathVariable Long id, @RequestBody TaskOnboardingReportRequest request) {
        return ApiResponse.ok(service.report(id, request));
    }

    @PostMapping("/result-validation/generate")
    public ApiResponse<TaskOnboardingResponse> generateResultValidation(@PathVariable Long id) {
        return ApiResponse.ok(service.generateResultValidation(id));
    }

    @PostMapping("/result-validation/confirm")
    public ApiResponse<TaskOnboardingResponse> confirmResultValidation(@PathVariable Long id) {
        return ApiResponse.ok(service.confirmResultValidation(id));
    }

    @PostMapping("/results/generate")
    public ApiResponse<TaskOnboardingResponse> generateResults(@PathVariable Long id) {
        return ApiResponse.ok(service.generateResults(id));
    }

    @PostMapping("/batch-validation/generate")
    public ApiResponse<TaskOnboardingResponse> generateBatchValidation(
            @PathVariable Long id,
            @RequestBody(required = false) GenerateTaskRunBatchRequest request) {
        return ApiResponse.ok(service.generateBatchValidation(id, request));
    }

    @PostMapping("/batch-validation/confirm")
    public ApiResponse<TaskOnboardingResponse> confirmBatchValidation(@PathVariable Long id) {
        return ApiResponse.ok(service.confirmBatchValidation(id));
    }

    @PostMapping("/batches/generate")
    public ApiResponse<TaskOnboardingResponse> generateBatches(
            @PathVariable Long id, @RequestBody GenerateTaskRunBatchRequest request) {
        return ApiResponse.ok(service.generateBatches(id, request));
    }
}
