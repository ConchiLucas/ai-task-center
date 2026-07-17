package com.aitaskcenter.controller;

import com.aitaskcenter.dto.AiActiveRequest;
import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.ExecutionTargetItem;
import com.aitaskcenter.dto.LocalCliConfigRequest;
import com.aitaskcenter.service.AiConfigService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiConfigController {
    private final AiConfigService service;

    // 方法：AiConfigController
    public AiConfigController(AiConfigService service) {
        this.service = service;
    }

    @GetMapping("/config")
    // 方法：getConfig
    public ApiResponse<AiConfigRequest> getConfig() {
        return ApiResponse.ok(service.getProviders());
    }

    @PostMapping("/config")
    // 方法：saveConfig
    public ApiResponse<List<AiProviderConfigItem>> saveConfig(@RequestBody AiConfigRequest request) {
        service.save(request);
        return ApiResponse.ok(service.getProviders().getProviders(), "保存成功");
    }

    @PostMapping("/config/active")
    // 方法：saveActive
    public ApiResponse<List<AiProviderConfigItem>> saveActive(@RequestBody AiActiveRequest request) {
        service.saveActive(request.getActive());
        return ApiResponse.ok(service.getProviders().getProviders(), "保存成功");
    }

    @GetMapping("/providers")
    // 方法：providers
    public ApiResponse<AiConfigRequest> providers() {
        return ApiResponse.ok(service.getProviders());
    }

    @GetMapping("/cli/config")
    // 方法：getLocalCliConfig
    public ApiResponse<LocalCliConfigRequest> getLocalCliConfig() {
        return ApiResponse.ok(service.getLocalCliConfig());
    }

    @PostMapping("/cli/config")
    // 方法：saveLocalCliConfig
    public ApiResponse<LocalCliConfigRequest> saveLocalCliConfig(@RequestBody LocalCliConfigRequest request) {
        return ApiResponse.ok(service.saveLocalCliConfig(request), "保存成功");
    }

    @GetMapping("/execution-targets")
    public ApiResponse<List<ExecutionTargetItem>> executionTargets() {
        return ApiResponse.ok(service.getExecutionTargets());
    }
}
