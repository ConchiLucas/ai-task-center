package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.DeleteByIdRequest;
import com.aitaskcenter.model.ProjectConfig;
import com.aitaskcenter.service.ProjectConfigService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/project")
public class ProjectConfigController {
    private final ProjectConfigService service;

    public ProjectConfigController(ProjectConfigService service) {
        this.service = service;
    }

    @GetMapping("/getTbInterfaceProjectList")
    public ApiResponse<List<ProjectConfig>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping("/createTbInterfaceProject")
    public ApiResponse<ProjectConfig> create(@RequestBody ProjectConfig input) {
        return ApiResponse.ok(service.create(input), "项目创建成功");
    }

    @PutMapping("/updateTbInterfaceProject")
    public ApiResponse<ProjectConfig> update(@RequestBody ProjectConfig input) {
        return ApiResponse.ok(service.update(input.getId(), input), "项目更新成功");
    }

    @DeleteMapping("/deleteTbInterfaceProject")
    public ApiResponse<Void> delete(@RequestBody DeleteByIdRequest request) {
        service.delete(request.getId());
        return ApiResponse.ok(null, "项目删除成功");
    }
}
