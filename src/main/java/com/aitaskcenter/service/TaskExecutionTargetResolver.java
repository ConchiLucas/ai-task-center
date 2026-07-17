package com.aitaskcenter.service;

import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TaskExecutionTargetResolver {
    public static final String HANDLER_SCORE = "word_clean_sentence_score";
    public static final String HANDLER_TTS = "word_clean_best_sentence_tts";
    public static final String EXECUTOR_CLI = "CLI";
    public static final String EXECUTOR_AI_PROVIDER = "AI_PROVIDER";
    public static final String DEFAULT_MIMO_TTS_PROVIDER = "xiaomi-mimo-tts";

    public ResolvedTarget resolve(
            String handlerKey,
            String executorType,
            String executorId,
            String legacyCliId,
            String selectedTables,
            String payloadTaskType) {
        String effectiveHandler = clean(handlerKey);
        if (!StringUtils.hasText(effectiveHandler)) {
            effectiveHandler = isTts(selectedTables, payloadTaskType) ? HANDLER_TTS : HANDLER_SCORE;
        }

        String effectiveType = clean(executorType).toUpperCase(Locale.ROOT);
        String effectiveId = clean(executorId);
        if (!StringUtils.hasText(effectiveType) || !StringUtils.hasText(effectiveId)) {
            if (HANDLER_TTS.equals(effectiveHandler)) {
                effectiveType = EXECUTOR_AI_PROVIDER;
                effectiveId = DEFAULT_MIMO_TTS_PROVIDER;
            } else {
                effectiveType = EXECUTOR_CLI;
                effectiveId = StringUtils.hasText(legacyCliId) ? legacyCliId.trim() : "codex";
            }
        }
        return new ResolvedTarget(effectiveHandler, effectiveType, effectiveId);
    }

    private static boolean isTts(String selectedTables, String payloadTaskType) {
        String tables = clean(selectedTables).toLowerCase(Locale.ROOT);
        String taskType = clean(payloadTaskType).toLowerCase(Locale.ROOT);
        return tables.contains("word_clean_best_sentence")
                || taskType.contains("word_clean_best_sentence_tts");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public record ResolvedTarget(String handlerKey, String executorType, String executorId) {}
}
