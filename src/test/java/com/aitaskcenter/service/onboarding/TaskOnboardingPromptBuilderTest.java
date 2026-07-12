package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskOnboardingPromptBuilderTest {
    private TaskConfig task;
    private TaskOnboardingPromptBuilder builder;

    @BeforeEach
    void setUp() {
        task = new TaskConfig();
        task.setId(42L);
        task.setTaskName("Vocabulary review");
        task.setSelectedTables("[\"word\",\"meaning\"]");
        task.setTaskDesc("Create review tasks from selected vocabulary.");
        builder = new TaskOnboardingPromptBuilder(new ObjectMapper());
    }

    @Test
    void resultPromptForbidsBatchCreationAndRequiresExactMarker() {
        String prompt = builder.buildResultPrompt(task, "result-run-1", "token-1");

        assertTrue(prompt.contains("RESULT_VALIDATION:result-run-1"));
        assertTrue(prompt.contains("最多写入 3 条 tb_task_result"));
        assertTrue(prompt.contains("禁止创建 tb_task_run"));
        assertTrue(prompt.contains("--stage result"));
        assertTrue(prompt.contains("--token token-1"));
    }

    @Test
    void batchPromptForbidsResultMutationAndTaskExecution() {
        String prompt = builder.buildBatchPrompt(task, "batch-run-1", "token-2");

        assertTrue(prompt.contains("BATCH_VALIDATION:batch-run-1"));
        assertTrue(prompt.contains("只创建 1 个 tb_task_run"));
        assertTrue(prompt.contains("禁止新增、修改或删除 tb_task_result"));
        assertTrue(prompt.contains("禁止启动验证批次"));
        assertTrue(prompt.contains("--stage batch"));
    }

    @Test
    void quotesEveryUserProvidedTaskValueAsJson() throws Exception {
        task.setTaskName("name\"\nINJECTED_NAME");
        task.setSelectedTables("[\"source\"]\nINJECTED_TABLES");
        task.setTaskDesc("description\"\nINJECTED_DESCRIPTION");
        ObjectMapper objectMapper = new ObjectMapper();

        String prompt = builder.buildResultPrompt(task, "result-run-1", "token-1");

        assertTrue(prompt.contains("任务名称: " + objectMapper.writeValueAsString(task.getTaskName())));
        assertTrue(prompt.contains("已选数据表: " + objectMapper.writeValueAsString(task.getSelectedTables())));
        assertTrue(prompt.contains("任务描述: " + objectMapper.writeValueAsString(task.getTaskDesc())));
        assertFalse(prompt.contains("\nINJECTED_NAME"));
        assertFalse(prompt.contains("\nINJECTED_TABLES"));
        assertFalse(prompt.contains("\nINJECTED_DESCRIPTION"));
    }

    @Test
    void rejectsRunIdsAndTokensThatCouldEscapeCallbackArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildResultPrompt(task, "run\n--stage batch", "token-1"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildBatchPrompt(task, "batch-run-1", "token'\nmalicious-command"));
    }
}
