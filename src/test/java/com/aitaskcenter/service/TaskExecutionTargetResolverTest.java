package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskExecutionLog;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import org.junit.jupiter.api.Test;

class TaskExecutionTargetResolverTest {
    private final TaskExecutionTargetResolver resolver = new TaskExecutionTargetResolver();

    @Test
    void explicitHandlerAndExecutorSnapshotWins() {
        TaskExecutionTargetResolver.ResolvedTarget target = resolver.resolve(
                "word_clean_sentence_score",
                "AI_PROVIDER",
                "openai-main",
                "codex",
                "[\"public.word_clean_best_sentence\"]",
                "word_clean_best_sentence_tts_batch");

        assertEquals("word_clean_sentence_score", target.handlerKey());
        assertEquals("AI_PROVIDER", target.executorType());
        assertEquals("openai-main", target.executorId());
    }

    @Test
    void legacyTtsTaskResolvesToMimoProvider() {
        TaskExecutionTargetResolver.ResolvedTarget target = resolver.resolve(
                null,
                null,
                null,
                "codex",
                "[\"public.word_clean_best_sentence\"]",
                null);

        assertEquals("word_clean_best_sentence_tts", target.handlerKey());
        assertEquals("AI_PROVIDER", target.executorType());
        assertEquals("xiaomi-mimo-tts", target.executorId());
    }

    @Test
    void legacyScoreTaskKeepsCliTarget() {
        TaskExecutionTargetResolver.ResolvedTarget target = resolver.resolve(
                null,
                null,
                null,
                "codex",
                "[\"public.word_clean_sentence\"]",
                null);

        assertEquals("word_clean_sentence_score", target.handlerKey());
        assertEquals("CLI", target.executorType());
        assertEquals("codex", target.executorId());
    }

    @Test
    void domainModelsExposeMigrationSnapshotFields() {
        TaskConfig config = new TaskConfig();
        config.setHandlerKey("word_clean_best_sentence_tts");
        config.setExecutorType("AI_PROVIDER");
        config.setExecutorId("xiaomi-mimo-tts");
        config.setOnboardingCliId("codex");

        TaskResult result = new TaskResult();
        result.setHandlerKey(config.getHandlerKey());
        result.setExecutorType(config.getExecutorType());
        result.setExecutorId(config.getExecutorId());

        TaskRun run = new TaskRun();
        run.setHandlerKey(result.getHandlerKey());
        run.setExecutorType(result.getExecutorType());
        run.setExecutorId(result.getExecutorId());

        TaskExecutionLog log = new TaskExecutionLog();
        log.setHandlerKey(run.getHandlerKey());
        log.setExecutorType(run.getExecutorType());
        log.setExecutorId(run.getExecutorId());
        log.setExecutorLabel("小米 MiMo TTS");

        assertEquals("word_clean_best_sentence_tts", log.getHandlerKey());
        assertEquals("AI_PROVIDER", log.getExecutorType());
        assertEquals("xiaomi-mimo-tts", log.getExecutorId());
        assertEquals("小米 MiMo TTS", log.getExecutorLabel());
    }
}
