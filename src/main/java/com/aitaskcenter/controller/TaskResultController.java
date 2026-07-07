package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.DeleteByIdRequest;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.service.TaskResultService;
import java.util.List;
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
@RequestMapping("/api/task-result")
public class TaskResultController {
    private final TaskResultService service;

    // 方法：TaskResultController
    public TaskResultController(TaskResultService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<TaskResult>> list(
            @RequestParam(required = false) String resultName,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(resultName, projectId, status));
    }

    @GetMapping("/{id}")
    // 方法：get
    public ApiResponse<TaskResult> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/create")
    // 方法：create
    public ApiResponse<TaskResult> create(@RequestBody TaskResult input) {
        return ApiResponse.ok(service.create(input), "任务结果创建成功");
    }

    @PutMapping("/update")
    // 方法：update
    public ApiResponse<TaskResult> update(@RequestBody TaskResult input) {
        return ApiResponse.ok(service.update(input.getId(), input), "任务结果更新成功");
    }

    @DeleteMapping("/delete")
    // 方法：delete
    public ApiResponse<Void> delete(@RequestBody DeleteByIdRequest request) {
        service.delete(request.getId());
        return ApiResponse.ok(null, "任务结果删除成功");
    }
}
