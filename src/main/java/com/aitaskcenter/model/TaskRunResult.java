package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "tb_task_run_result",
        indexes = {
                @Index(name = "idx_task_run_result_run", columnList = "task_run_id"),
                @Index(name = "idx_task_run_result_result", columnList = "task_result_id"),
                @Index(name = "idx_task_run_result_status", columnList = "status")
        })
public class TaskRunResult extends BaseEntity {
    @Column(nullable = false)
    // 字段：任务执行批次 ID
    private Long taskRunId;

    @Column(nullable = false)
    // 字段：任务结果 ID
    private Long taskResultId;

    @Column(nullable = false)
    // 字段：批次内任务结果状态
    private String status = "PENDING";

    @Column(length = 4000)
    // 字段：批次内任务结果错误信息
    private String errorMessage;

    // 方法：getTaskRunId
    public Long getTaskRunId() {
        return taskRunId;
    }

    // 方法：setTaskRunId
    public void setTaskRunId(Long taskRunId) {
        this.taskRunId = taskRunId;
    }

    // 方法：getTaskResultId
    public Long getTaskResultId() {
        return taskResultId;
    }

    // 方法：setTaskResultId
    public void setTaskResultId(Long taskResultId) {
        this.taskResultId = taskResultId;
    }

    // 方法：getStatus
    public String getStatus() {
        return status;
    }

    // 方法：setStatus
    public void setStatus(String status) {
        this.status = status;
    }

    // 方法：getErrorMessage
    public String getErrorMessage() {
        return errorMessage;
    }

    // 方法：setErrorMessage
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
