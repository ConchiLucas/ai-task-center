package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class TaskOnboardingPromptBuilder {
    private final ObjectMapper objectMapper;

    public TaskOnboardingPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String buildResultPrompt(TaskConfig task, String validationRunId, String token) {
        String safeRunId = safeIdentifier(validationRunId, "validationRunId");
        String safeToken = safeIdentifier(token, "token");
        String marker = "RESULT_VALIDATION:" + safeRunId;
        return """
                为以下任务定制并验证任务结果生成代码。

                任务配置 ID: %s
                任务名称: %s
                已选数据表: %s
                任务描述: %s
                精确验证标记: %s

                严格要求：
                - 读取真实来源数据，最多写入 3 条 tb_task_result，且至少写入 1 条。
                - 每条结果的 task_config_id 必须为当前任务配置 ID。
                - 每条结果的 source_description 必须精确等于上述验证标记。
                - 每条 result_content._meta.validationRunId 必须保存上述完整验证标记。
                - 禁止创建 tb_task_run 或 tb_task_run_result。
                - 禁止修改或删除来源业务表，禁止执行任务，禁止调用 AI 或 TTS。
                - 完成代码、自测和验证数据写入后，必须执行以下完整命令向任务中心报告状态；Web 页面不会监控 Codex：

                ./scripts/task-workflow report \\
                  --task-config-id %s \\
                  --stage result \\
                  --token %s \\
                  --artifact 'path/to/generated-artifact' \\
                  --artifact-hash 'sha256-of-generated-artifact' \\
                  --entity-ids 'comma-separated-result-ids'
                """.formatted(
                task.getId(),
                json(task.getTaskName()),
                json(task.getSelectedTables()),
                json(task.getTaskDesc()),
                json(marker),
                task.getId(),
                safeToken);
    }

    public String buildBatchPrompt(TaskConfig task, String validationRunId, String token) {
        String safeRunId = safeIdentifier(validationRunId, "validationRunId");
        String safeToken = safeIdentifier(token, "token");
        String marker = "BATCH_VALIDATION:" + safeRunId;
        return """
                为以下任务定制并验证任务批次生成代码。

                任务配置 ID: %s
                任务名称: %s
                已选数据表: %s
                任务描述: %s
                精确验证标记: %s

                严格要求：
                - 只创建 1 个 tb_task_run，且该验证批次必须保持未启动、未执行状态。
                - 该批次的 task_config_id 必须为当前任务配置 ID，reason 必须精确等于上述验证标记。
                - 批次 Prompt 的 _meta.validationRunId 必须保存上述完整验证标记。
                - tb_task_run_result 只能关联当前任务配置下已经存在的正式 tb_task_result。
                - 禁止新增、修改或删除 tb_task_result。
                - 禁止启动验证批次，禁止执行任务，禁止调用 AI 或 TTS，禁止回写来源业务表。
                - 完成代码、自测和验证批次写入后，必须执行以下完整命令向任务中心报告状态；Web 页面不会监控 Codex：

                ./scripts/task-workflow report \\
                  --task-config-id %s \\
                  --stage batch \\
                  --token %s \\
                  --artifact 'path/to/generated-artifact' \\
                  --artifact-hash 'sha256-of-generated-artifact' \\
                  --entity-ids 'validation-run-id,comma-separated-linked-result-ids'
                """.formatted(
                task.getId(),
                json(task.getTaskName()),
                json(task.getSelectedTables()),
                json(task.getTaskDesc()),
                json(marker),
                task.getId(),
                safeToken);
    }

    private String json(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode prompt value", exception);
        }
    }

    private String safeIdentifier(String value, String fieldName) {
        if (value == null || !value.matches("[A-Za-z0-9._:+-]+")) {
            throw new IllegalArgumentException(fieldName + " contains unsafe characters");
        }
        return value;
    }
}
