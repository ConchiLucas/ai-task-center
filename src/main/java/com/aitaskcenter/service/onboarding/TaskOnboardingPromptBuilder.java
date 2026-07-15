package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TaskOnboardingPromptBuilder {
    private final ObjectMapper objectMapper;

    public TaskOnboardingPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(TaskConfig task, OnboardingStep step, String token) {
        Map<String, Object> taskContext = new LinkedHashMap<>();
        taskContext.put("taskConfigId", task.getId());
        taskContext.put("taskName", task.getTaskName());
        taskContext.put("taskDesc", task.getTaskDesc());
        taskContext.put("selectedTables", task.getSelectedTables());
        taskContext.put("databaseConfigId", task.getDatabaseConfigId());
        String stage = step == OnboardingStep.RESULT_CODE ? "result" : "batch";
        String target = step == OnboardingStep.RESULT_CODE ? "任务结果生成" : "执行批次生成";
        try {
            return """
                    请为下面的任务配置检查并完善%s代码：
                    %s

                    本步骤只允许修改代码、补充测试并重启受影响的服务。
                    不要插入、更新或删除 tb_task_result、tb_task_run、tb_task_run_result，
                    不要生成验证数据，也不要启动任务。验证数据将由用户在任务中心手动生成。

                    完成修改和测试后执行：
                    ./scripts/task-workflow report --task-config-id %d --stage %s --token '%s' --status CODE_READY
                    """.formatted(target, objectMapper.writeValueAsString(taskContext), task.getId(), stage, token);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("生成任务引导提示词失败", ex);
        }
    }
}
