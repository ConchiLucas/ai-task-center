package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aitaskcenter.model.TaskResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRunPromptBuilderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskRunPromptBuilder builder = new TaskRunPromptBuilder(objectMapper);

    @Test
    void buildsTtsBatchExecutionPayloadWithoutLegacyJobTable() throws Exception {
        TaskResult first = ttsResult(11L, 101L, 7L, "example", "This is an example.");
        TaskResult second = ttsResult(12L, 102L, 8L, "sample", "This is a sample.");

        String json = builder.buildBatchPromptJson(
                new TaskRunPromptBuilder.BatchPromptContext(
                        "生成 TTS 任务 - 验证 - 批次 1",
                        "codex",
                        "[\"public.word_clean_best_sentence\"]"),
                List.of(first, second));

        JsonNode root = objectMapper.readTree(json);
        assertEquals("word_clean_best_sentence_tts_batch", root.path("taskType").asText());
        assertEquals(2, root.path("batch").path("resultCount").asInt());
        assertFalse(root.path("rules").path("callCli").asBoolean());
        assertFalse(root.path("rules").path("legacyJobTableDependency").asBoolean());
        assertEquals("item_A", root.path("items").get(0).path("itemKey").asText());
        assertEquals(101L, root.path("items").get(0).path("bestSentenceId").asLong());
        assertEquals("This is an example.", root.path("items").get(0).path("ttsInput").path("text").asText());
        assertEquals("public.word_clean_best_sentence", root.path("items").get(0).path("sourceTable").asText());
        assertTrue(json.contains("AI Task Center"));
        assertFalse(json.contains("word_clean_sentence_tts_job"));
    }

    @Test
    void keepsScoreBatchContractForScoreResults() throws Exception {
        TaskResult scoreResult = new TaskResult();
        scoreResult.setId(21L);
        scoreResult.setResultContent("""
                {
                  "taskType":"word_clean_sentence_score",
                  "word":"example",
                  "sourceTable":"public.word_clean_sentence",
                  "writeBack":{"word":"example","candidateMap":{"A":{"sentence":"Example."}}}
                }
                """);

        JsonNode root = objectMapper.readTree(builder.buildBatchPromptJson(
                new TaskRunPromptBuilder.BatchPromptContext("评分批次", "codex", "[]"),
                List.of(scoreResult)));

        assertEquals("word_clean_sentence_score_batch", root.path("taskType").asText());
        assertEquals("word_clean_sentence_score", root.path("items").get(0).path("taskType").asText());
        assertEquals("A", root.path("items").get(0).path("candidates").get(0).path("candidate").asText());
    }

    @Test
    void rejectsMixingScoreAndTtsResultsInOneBatch() {
        TaskResult scoreResult = new TaskResult();
        scoreResult.setId(21L);
        scoreResult.setResultContent("{\"taskType\":\"word_clean_sentence_score\"}");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> builder.buildBatchPromptJson(
                        new TaskRunPromptBuilder.BatchPromptContext("混合批次", "codex", "[]"),
                        List.of(scoreResult, ttsResult(11L, 101L, 7L, "example", "Example."))));

        assertEquals("同一执行批次不能混合不同任务类型", exception.getMessage());
    }

    private static TaskResult ttsResult(
            Long resultId,
            Long bestSentenceId,
            Long wordCleanId,
            String word,
            String sentence) {
        TaskResult result = new TaskResult();
        result.setId(resultId);
        result.setResultContent("""
                {
                  "taskType":"word_clean_best_sentence_tts",
                  "bestSentenceId":%d,
                  "wordCleanId":%d,
                  "word":"%s",
                  "sourceTable":"public.word_clean_best_sentence",
                  "source":{"sourceSentenceId":501,"sentence":"%s"},
                  "ttsInput":{"text":"%s","fileName":"best-%d.wav","defaultAudioFormat":"wav"},
                  "writeBack":{"table":"public.word_clean_best_sentence","bestSentenceId":%d}
                }
                """.formatted(
                bestSentenceId,
                wordCleanId,
                word,
                sentence,
                sentence,
                bestSentenceId,
                bestSentenceId));
        return result;
    }
}
