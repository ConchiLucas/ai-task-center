package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.ObjectStorageConfigRequest;
import com.aitaskcenter.dto.ObjectStorageConfigResponse;
import com.aitaskcenter.service.ObjectStorageConfigService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/object-storage-config")
public class ObjectStorageConfigController {
    private final ObjectStorageConfigService service;

    public ObjectStorageConfigController(ObjectStorageConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ObjectStorageConfigResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<ObjectStorageConfigResponse> create(@RequestBody ObjectStorageConfigRequest request) {
        return ApiResponse.ok(service.create(request), "对象存储配置添加成功");
    }

    @PutMapping("/{id}")
    public ApiResponse<ObjectStorageConfigResponse> update(
            @PathVariable Long id,
            @RequestBody ObjectStorageConfigRequest request) {
        return ApiResponse.ok(service.update(id, request), "对象存储配置修改成功");
    }

    @PutMapping("/{id}/default")
    public ApiResponse<ObjectStorageConfigResponse> setDefault(@PathVariable Long id) {
        return ApiResponse.ok(service.setDefault(id), "默认对象存储配置已更新");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null, "删除成功");
    }
}
