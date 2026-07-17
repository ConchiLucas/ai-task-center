package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.BatchDeleteRequest;
import com.aitaskcenter.dto.CreateTaskRunRequest;
import com.aitaskcenter.dto.DeleteByIdRequest;
import com.aitaskcenter.dto.StartTaskRunRequest;
import com.aitaskcenter.model.TaskRecordType;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.service.TaskRunService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/task-run")
public class TaskRunController {
    private final TaskRunService service;

    // 方法：TaskRunController
    public TaskRunController(TaskRunService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<TaskRun>> list(
            @RequestParam(required = false) String taskName,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long taskConfigId,
            @RequestParam(required = false) String cliId,
            @RequestParam(required = false) String executorType,
            @RequestParam(required = false) String executorId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = TaskRecordType.FORMAL) String recordType) {
        return ApiResponse.ok(service.list(
                taskName, projectId, taskConfigId, cliId, executorType, executorId, status, recordType));
    }

    @PostMapping("/create")
    // 方法：create
    public ApiResponse<TaskRun> create(@RequestBody CreateTaskRunRequest request) {
        return ApiResponse.ok(service.create(request), "任务已创建");
    }

    @PostMapping("/start")
    // 方法：start
    public ApiResponse<Map<String, Object>> start(@RequestBody StartTaskRunRequest request) {
        return ApiResponse.ok(service.startExecution(request), "任务已提交执行");
    }

    @PostMapping("/{id}/retry")
    // 方法：retry
    public ApiResponse<TaskRun> retry(@PathVariable Long id) {
        return ApiResponse.ok(service.retry(id), "已恢复为待执行");
    }

    @PostMapping("/{id}/cancel")
    // 方法：cancel
    public ApiResponse<TaskRun> cancel(@PathVariable Long id) {
        return ApiResponse.ok(service.cancel(id), "任务已取消");
    }

    @GetMapping("/{id}/log")
    // 方法：log
    public ApiResponse<TaskRun> log(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/{id}/detail")
    // 方法：detail
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @DeleteMapping("/delete")
    // 方法：delete
    public ApiResponse<Void> delete(@RequestBody DeleteByIdRequest request) {
        service.delete(request.getId());
        return ApiResponse.ok(null, "任务记录删除成功");
    }

    @DeleteMapping("/batchDelete")
    // 方法：batchDelete
    public ApiResponse<Void> batchDelete(@RequestBody BatchDeleteRequest request) {
        service.batchDelete(request.getIds());
        return ApiResponse.ok(null, "任务记录批量删除成功");
    }
}
