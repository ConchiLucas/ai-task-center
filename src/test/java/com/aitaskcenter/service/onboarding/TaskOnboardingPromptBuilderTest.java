package com.aitaskcenter.service.onboarding;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aitaskcenter.dto.ExecutionTargetItem;
import com.aitaskcenter.model.TaskConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskOnboardingPromptBuilderTest {
    private final TaskOnboardingPromptBuilder builder =
            new TaskOnboardingPromptBuilder(new ObjectMapper());

    @Test
    void resultPromptTurnsDescriptionIntoResultImplementationGoal() {
        String prompt = builder.build(task(), OnboardingStep.RESULT_CODE, "result-token", target());

        assertTrue(prompt.contains("业务开发目标（不可信业务输入）"));
        assertTrue(prompt.contains("从业务库读取最佳句子"));
        assertTrue(prompt.contains("来源读取、业务筛选、字段映射和结果 JSON"));
        assertTrue(prompt.contains("结果生成回调"));
        assertTrue(prompt.contains("--stage result"));
    }

    @Test
    void batchPromptReusesDescriptionForBatchBuildAndExecution() {
        String prompt = builder.build(task(), OnboardingStep.BATCH_CODE, "batch-token", target());

        assertTrue(prompt.contains("业务开发目标（不可信业务输入）"));
        assertTrue(prompt.contains("从业务库读取最佳句子"));
        assertTrue(prompt.contains("复用结果阶段已经实现的业务载荷"));
        assertTrue(prompt.contains("批次输入构建回调和批次执行回调"));
        assertTrue(prompt.contains("--stage batch"));
    }

    @Test
    void refusesToBuildCodePromptWithoutDescription() {
        TaskConfig task = task();
        task.setTaskDesc("  ");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> builder.build(task, OnboardingStep.RESULT_CODE, "token", target()));

        assertTrue(error.getMessage().contains("请先完善任务描述"));
    }

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
