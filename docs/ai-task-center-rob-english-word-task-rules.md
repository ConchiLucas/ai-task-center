---
doc_id: ai-task-center-rob-english-word-task-rules
title: rob_english_word_workforce 任务生成规则
doc_type: task_rules
area: business
tags: [rob_english_word, task, scoring, tts]
---

# rob_english_word_workforce 任务生成规则

## 任务中心规则

`word_clean_sentence_score_job` 属于旧业务库任务表，任务状态和执行编排迁移到 AI Task Center 后，不再把它作为任务来源。

AI Task Center 只从以下业务数据表生成任务：

| 任务 | 来源表 | 目标 |
| --- | --- | --- |
| 单词评分任务 | `public.word_clean_sentence` | 读取候选句，评分后回写候选句评分，并维护最佳句子 |
| 生成 TTS 任务 | `public.word_clean_best_sentence` | 读取最佳句子，生成音频后回写 TTS 结果 |

## 已配置任务

| 任务配置 | 数据源 | 选中表 |
| --- | --- | --- |
| 单词评分任务 | `rob_english_word PostgreSQL` | `public.word_clean_sentence` |
| 生成 TTS 任务 | `rob_english_word PostgreSQL` | `public.word_clean_best_sentence` |

## 第一版生成结果链路

任务配置列表中的“生成结果”按钮第一版只支持 `public.word_clean_sentence`。

链路：

1. 前端点击任务配置行的“生成结果”。
2. Java 后端调用 Python Worker：`POST /api/result-generation/from-task-config-simple`。
3. Python Worker 从任务中心库读取任务配置和数据库配置。
4. Python Worker 连接 `rob_english_word`，按 `word_clean_id` 聚合 `public.word_clean_sentence`。
5. 每个 `word_clean_id` 生成一条 `tb_task_result`，重复点击会跳过已生成结果。
6. Python Worker 为任务配置生成专属脚本：`.runtime/generated-scripts/task-config-<id>/score_word_clean_sentence.py`。

## 迁移注意

- 不要再从 `word_clean_sentence_score_job` 生成任务。
- `word_clean_sentence_score_job` 可以在新任务中心链路验证完成后清理，但清理前需要用户明确确认。
- TTS 任务依赖评分任务产出的 `word_clean_best_sentence` 数据。
