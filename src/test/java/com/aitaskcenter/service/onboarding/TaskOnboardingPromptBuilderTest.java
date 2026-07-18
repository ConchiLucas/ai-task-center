package com.aitaskcenter.service.onboarding;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aitaskcenter.dto.ExecutionTargetItem;
import com.aitaskcenter.model.TaskConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskOnboardingPromptBuilderTest {
    private final TaskOnboardingPromptBuilder builder =
            new TaskOnboardingPromptBuilder(new ObjectMapper());

    @Test
    void batchPromptPinsChangesToTaskCenterAndPythonWorker() {
        String prompt = builder.build(task(), OnboardingStep.BATCH_CODE, "batch-token", target());

        assertTrue(prompt.contains("只允许修改当前 AI Task Center Git 仓库"));
        assertTrue(prompt.contains("禁止修改任何其他仓库"));
        assertTrue(prompt.contains("所有 AI、LLM、TTS"));
        assertTrue(prompt.contains("python-worker"));
        assertTrue(prompt.contains("BEGIN UNTRUSTED BUSINESS DATA"));
        assertTrue(prompt.contains("END UNTRUSTED BUSINESS DATA"));
        assertTrue(prompt.contains("业务项目、数据源和表名只用于读取业务数据"));
        assertTrue(prompt.contains("git rev-parse --show-toplevel"));
        assertTrue(prompt.contains("task_config_1"));
        assertTrue(prompt.contains("任意外部编码工具"));
        assertTrue(prompt.contains("TEXT_GENERATION"));
        assertTrue(!prompt.toLowerCase().contains("api_key"));
    }

    @Test
    void resultPromptUsesTheSameRepositoryAndArchitectureBoundary() {
        String prompt = builder.build(task(), OnboardingStep.RESULT_CODE, "result-token", target());

        assertTrue(prompt.contains("只允许修改当前 AI Task Center Git 仓库"));
        assertTrue(prompt.contains("所有 AI、LLM、TTS"));
        assertTrue(prompt.contains("python-worker"));
        assertTrue(prompt.contains("UNTRUSTED BUSINESS DATA"));
    }

    private TaskConfig task() {
        TaskConfig task = new TaskConfig();
        task.setId(1L);
        task.setTaskName("生成 TTS 任务");
        task.setTaskDesc("从业务库读取最佳句子");
        task.setProjectId(2L);
        task.setDatabaseConfigId(3L);
        task.setSelectedTables("[\"public.word_clean_best_sentence\"]");
        task.setExecutorType("CLI");
        task.setExecutorId("codex");
        return task;
    }

    private ExecutionTargetItem target() {
        return new ExecutionTargetItem(
                "CLI", "codex", "Codex CLI", "local-cli", List.of("TEXT_GENERATION"), true);
    }
}
