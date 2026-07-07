package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.DeleteByIdRequest;
import com.aitaskcenter.dto.PageResult;
import com.aitaskcenter.model.ConnectionConfig;
import com.aitaskcenter.service.ConnectionConfigService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/connection")
public class ConnectionConfigController {
    private final ConnectionConfigService service;

    // 方法：ConnectionConfigController
    public ConnectionConfigController(ConnectionConfigService service) {
        this.service = service;
    }

    @GetMapping("/getTbConnectionList")
    public ApiResponse<PageResult<ConnectionConfig>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "999") int pageSize,
            @RequestParam(required = false) String connectionGroup,
            @RequestParam(required = false) String envName) {
        return ApiResponse.ok(service.list(page, pageSize, connectionGroup, envName));
    }

    @PostMapping("/createTbConnection")
    // 方法：create
    public ApiResponse<ConnectionConfig> create(@RequestBody ConnectionConfig input) {
        return ApiResponse.ok(service.create(input), "数据库配置添加成功");
    }

    @PutMapping("/updateTbConnection")
    // 方法：update
    public ApiResponse<ConnectionConfig> update(@RequestBody ConnectionConfig input) {
        return ApiResponse.ok(service.update(input.getId(), input), "数据库配置修改成功");
    }

    @DeleteMapping("/deleteTbConnection")
    // 方法：delete
    public ApiResponse<Void> delete(@RequestBody DeleteByIdRequest request) {
        service.delete(request.getId());
        return ApiResponse.ok(null, "删除成功");
    }

    @GetMapping("/testConnection")
    // 方法：testById
    public ApiResponse<Void> testById(@RequestParam("ID") Long id) {
        service.testById(id);
        return ApiResponse.ok(null, "连接成功");
    }

    @PostMapping("/testConnectionPayload")
    // 方法：testPayload
    public ApiResponse<Void> testPayload(@RequestBody ConnectionConfig input) {
        service.test(input);
        return ApiResponse.ok(null, "连接成功");
    }

    @GetMapping("/listTables")
    // 方法：listTables
    public ApiResponse<List<String>> listTables(@RequestParam("ID") Long id) {
        return ApiResponse.ok(service.listTables(id));
    }
}
