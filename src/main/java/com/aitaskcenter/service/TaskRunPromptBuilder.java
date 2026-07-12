package com.aitaskcenter.service;

import com.aitaskcenter.model.TaskResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TaskRunPromptBuilder {
    private static final int SCORE_MIN = 1;
    private static final int SCORE_MAX = 100;
    private final ObjectMapper objectMapper;

    public record BatchPromptContext(
            String taskName,
            String cliId,
            String selectedTables) {
    }

    // 方法：TaskRunPromptBuilder
    public TaskRunPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // 方法：buildBatchPromptJson
    public String buildBatchPromptJson(BatchPromptContext context, List<TaskResult> results) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("taskType", "word_clean_sentence_score_batch");
            root.put("version", 1);
            root.put("batch", buildBatchMeta(context, results));
            root.put("instructions", buildInstructions());
            root.put("rules", buildRules());
            List<Map<String, Object>> items = buildItems(results);
            root.put("items", items);
            root.put("responseSchema", buildResponseSchema(items));
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalArgumentException("生成批次 AI 提示词失败: " + ex.getMessage(), ex);
        }
    }

    // 方法：buildBatchMeta
    private Map<String, Object> buildBatchMeta(BatchPromptContext context, List<TaskResult> results) {
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("taskName", clean(context.taskName()));
        batch.put("cliId", clean(context.cliId()));
        batch.put("selectedTables", clean(context.selectedTables()));
        batch.put("resultCount", results.size());
        return batch;
    }

    // 方法：buildInstructions
    private List<String> buildInstructions() {
        return List.of(
                "你是英语例句质量评审助手。",
                "请为 items 中每一个任务结果独立评分。",
                "每个 item 代表一个目标单词及其候选例句。",
                "请只返回 JSON，不要返回 Markdown、解释性文字或数据库 ID。",
                "返回 items 必须覆盖输入中的全部 itemKey。",
                "每个 item 的 scores 必须覆盖该 item 的全部候选 candidate。",
                "bestCandidate 必须等于该 item 中 score 最高的候选。");
    }

    // 方法：buildRules
    private Map<String, Object> buildRules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("scoreMin", SCORE_MIN);
        rules.put("scoreMax", SCORE_MAX);
        rules.put("uniqueScorePerItem", true);
        rules.put("returnOnlyJson", true);
        rules.put("doNotReturnDatabaseIds", true);
        return rules;
    }

    // 方法：buildItems
    private List<Map<String, Object>> buildItems(List<TaskResult> results) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            TaskResult result = results.get(index);
            JsonNode payload = objectMapper.readTree(result.getResultContent());
            JsonNode writeBack = payload.path("writeBack");
            JsonNode candidateMap = writeBack.path("candidateMap");
            if (!candidateMap.isObject() || candidateMap.isEmpty()) {
                throw new IllegalArgumentException("任务结果缺少 candidateMap: " + result.getId());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemKey", itemKey(index));
            item.put("taskType", "word_clean_sentence_score");
            item.put("word", promptSafeText(text(payload, "word", text(writeBack, "word", ""))));
            item.put("sourceTable", text(payload, "sourceTable", text(writeBack, "sourceTable", "")));
            item.put("candidates", buildCandidates(candidateMap));
            items.add(item);
        }
        return items;
    }

    // 方法：buildCandidates
    private List<Map<String, Object>> buildCandidates(JsonNode candidateMap) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = candidateMap.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode source = field.getValue();
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("candidate", field.getKey());
            candidate.put("modelName", promptSafeText(text(source, "modelName", "")));
            candidate.put("sentence", promptSafeText(text(source, "sentence", "")));
            candidate.put("sentenceTranslation", promptSafeText(text(source, "sentenceTranslation", "")));
            candidates.add(candidate);
        }
        return candidates;
    }

    // 方法：buildResponseSchema
    private Map<String, Object> buildResponseSchema(List<Map<String, Object>> items) {
        List<Map<String, Object>> schemaItems = new ArrayList<>();
        for (Map<String, Object> item : items) {
            List<?> candidates = item.get("candidates") instanceof List<?> list ? list : List.of();
            String firstCandidate = candidates.isEmpty()
                    ? "A"
                    : String.valueOf(((Map<?, ?>) candidates.get(0)).get("candidate"));
            Map<String, Object> score = new LinkedHashMap<>();
            score.put("candidate", firstCandidate);
            score.put("score", 95);
            score.put("reason", "简短原因");
            Map<String, Object> schemaItem = new LinkedHashMap<>();
            schemaItem.put("itemKey", item.get("itemKey"));
            schemaItem.put("scores", List.of(score));
            schemaItem.put("bestCandidate", firstCandidate);
            schemaItems.add(schemaItem);
        }
        return Map.of("items", schemaItems);
    }

    // 方法：itemKey
    private static String itemKey(int index) {
        return "item_" + alphabeticIndex(index);
    }

    // 方法：alphabeticIndex
    private static String alphabeticIndex(int index) {
        StringBuilder builder = new StringBuilder();
        int value = index;
        while (value >= 0) {
            int remainder = value % 26;
            builder.insert(0, (char) ('A' + remainder));
            value = value / 26 - 1;
        }
        return builder.toString();
    }

    // 方法：text
    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        return value.asText(fallback);
    }

    // 方法：clean
    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    // 方法：promptSafeText
    private static String promptSafeText(String value) {
        return clean(value)
                .replace("0", "零")
                .replace("1", "一")
                .replace("2", "二")
                .replace("3", "三")
                .replace("4", "四")
                .replace("5", "五")
                .replace("6", "六")
                .replace("7", "七")
                .replace("8", "八")
                .replace("9", "九");
    }
}
