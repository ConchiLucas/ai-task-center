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

| 任务 | 来源表 | 处理器 | 运行调用通道 |
| --- | --- | --- | --- |
| 单词评分任务 | `public.word_clean_sentence` | `word_clean_sentence_score` | CLI 或具备 `TEXT_GENERATION` 能力的 AI Provider |
| 生成 TTS 任务 | `public.word_clean_best_sentence` | `word_clean_best_sentence_tts` | 具备 `AUDIO_TTS` 能力的 MiMo AI Provider |

## 已配置任务

| 任务配置 | 数据源 | 选中表 |
| --- | --- | --- |
| 单词评分任务 | `rob_english_word PostgreSQL` | `public.word_clean_sentence` |
| 生成 TTS 任务 | `rob_english_word PostgreSQL` | `public.word_clean_best_sentence` |

## 处理器与模型边界

- 业务类型由 Python Worker 注册处理器决定，不由新建任务表单中的枚举决定。
- 内置评分与 TTS 处理器分别为 `word_clean_sentence_score` 和 `word_clean_best_sentence_tts`；后续自定义任务使用 `task_config_<任务配置ID>`。
- CLI 和 AI Provider 都只是模型调用通道。评分可选择具备 `TEXT_GENERATION` 能力的目标，TTS 必须选择具备 `AUDIO_TTS` 能力的目标。
- 生成结果时把处理器和模型目标写入结果快照；生成批次时原样复制，执行阶段不接受 CLI 覆盖，也不再根据表名或载荷补推目标。
- 所有模型、MiMo TTS 和本地 CLI 调用都位于 `python-worker`；Java 只负责编排、校验和持久化。

## 生成结果链路

链路：

1. 前端点击任务配置行的“生成结果”。
2. Java 后端调用 Python Worker：`POST /api/result-generation/from-task-config-simple`。
3. Python Worker 从任务中心库读取任务配置和数据库配置。
4. Python Worker 连接 `rob_english_word`，按 `word_clean_id` 聚合 `public.word_clean_sentence`。
5. 每个 `word_clean_id` 生成一条 `tb_task_result`，重复点击会跳过已生成结果。
6. Worker 通过注册表调用当前 `handlerKey` 对应的结果生成器；未知处理器或能力不匹配时拒绝接入和执行。

## 迁移注意

- 不要再从 `word_clean_sentence_score_job` 生成任务。
- `word_clean_sentence_score_job` 可以在新任务中心链路验证完成后清理，但清理前需要用户明确确认。
- TTS 任务依赖评分任务产出的 `word_clean_best_sentence` 数据。
