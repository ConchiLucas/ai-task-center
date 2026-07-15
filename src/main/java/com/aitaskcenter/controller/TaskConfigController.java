package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.DeleteByIdRequest;
import com.aitaskcenter.dto.GenerateTaskRunBatchRequest;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.service.TaskConfigService;
import com.aitaskcenter.service.onboarding.TaskOnboardingService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/task")
public class TaskConfigController {
    private final TaskConfigService service;
    private final TaskOnboardingService onboardingService;

    // 方法：TaskConfigController
    public TaskConfigController(TaskConfigService service, TaskOnboardingService onboardingService) {
        this.service = service;
        this.onboardingService = onboardingService;
    }

    @GetMapping("/getTaskConfigList")
    // 方法：list
    public ApiResponse<List<TaskConfig>> list(@RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(service.list(projectId));
    }

    @PostMapping("/createTaskConfig")
    // 方法：create
    public ApiResponse<TaskConfig> create(@RequestBody TaskConfig input) {
        return ApiResponse.ok(service.create(input), "任务配置创建成功");
    }

    @PostMapping("/{id}/generate-results")
    // 方法：generateResults
    public ApiResponse<Map<String, Object>> generateResults(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean overwrite) {
        if (overwrite) {
            throw new IllegalArgumentException("当前任务接入流程暂不处理已有正式数据覆盖");
        }
        var response = onboardingService.generateResults(id);
        return ApiResponse.ok(
                Map.of("insertedCount", response.getCounts().getOrDefault("insertedCount", 0L)),
                "任务结果生成完成");
    }

    @PostMapping("/{id}/generate-run-batches")
    // 方法：generateRunBatches
    public ApiResponse<Map<String, Object>> generateRunBatches(
            @PathVariable Long id,
            @RequestBody GenerateTaskRunBatchRequest request) {
        var response = onboardingService.generateBatches(id, request);
        return ApiResponse.ok(
                Map.of(
                        "createdRunCount", response.getCounts().getOrDefault("createdRunCount", 0L),
                        "linkedResultCount", response.getCounts().getOrDefault("linkedResultCount", 0L)),
                "执行批次生成完成");
    }

    @PutMapping("/updateTaskConfig")
    // 方法：update
    public ApiResponse<TaskConfig> update(@RequestBody TaskConfig input) {
        return ApiResponse.ok(service.update(input.getId(), input), "任务配置更新成功");
    }

    @DeleteMapping("/deleteTaskConfig")
    // 方法：delete
    public ApiResponse<Void> delete(@RequestBody DeleteByIdRequest request) {
        service.delete(request.getId());
        return ApiResponse.ok(null, "任务配置删除成功");
    }
}
