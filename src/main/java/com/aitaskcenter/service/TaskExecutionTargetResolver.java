package com.aitaskcenter.service;

import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TaskExecutionTargetResolver {
    public static final String EXECUTOR_CLI = "CLI";
    public static final String EXECUTOR_AI_PROVIDER = "AI_PROVIDER";

    public ResolvedTarget require(
            String handlerKey,
            String executorType,
            String executorId) {
        if (!StringUtils.hasText(handlerKey)) {
            throw new IllegalArgumentException("任务处理器未注册");
        }
        if (!StringUtils.hasText(executorType) || !StringUtils.hasText(executorId)) {
            throw new IllegalArgumentException("任务未配置模型调用通道");
        }
        return new ResolvedTarget(
                handlerKey.trim(),
                executorType.trim().toUpperCase(Locale.ROOT),
                executorId.trim());
    }

    public record ResolvedTarget(String handlerKey, String executorType, String executorId) {}
}
