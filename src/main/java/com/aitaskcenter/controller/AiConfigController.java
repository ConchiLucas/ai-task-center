package com.aitaskcenter.controller;

import com.aitaskcenter.dto.AiActiveRequest;
import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.ApiResponse;
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

    public AiConfigController(AiConfigService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public ApiResponse<AiConfigRequest> getConfig() {
        return ApiResponse.ok(service.getConfig());
    }

    @PostMapping("/config")
    public ApiResponse<List<AiProviderConfigItem>> saveConfig(@RequestBody AiConfigRequest request) {
        return ApiResponse.ok(service.save(request), "保存成功");
    }

    @PostMapping("/config/active")
    public ApiResponse<List<AiProviderConfigItem>> saveActive(@RequestBody AiActiveRequest request) {
        return ApiResponse.ok(service.saveActive(request.getActive()), "保存成功");
    }

    @GetMapping("/providers")
    public ApiResponse<AiConfigRequest> providers() {
        return ApiResponse.ok(service.getProviders());
    }
}
