# TTS 结果生成设计

## 目标

为任务配置 1（“生成 TTS 任务”）提供最小结果生成器。生成器从
`public.word_clean_best_sentence` 读取真实的待处理最佳句子，并按项目既有
`tb_task_result` 生成模式创建 TTS 待处理结果。

## 边界与安全

- 仅支持选择了 `public.word_clean_best_sentence` 的任务配置。
- 只读取来源表；不写来源表、不执行任务、不调用 AI 或 TTS。
- 任务生成器不创建或修改 `tb_task_run` 或 `tb_task_run_result`。
- 正常调用继续支持既有的幂等语义；验证阶段由调用方只插入最多三条带验证标记的结果。
- 不修改 `scripts/task-workflow` 或其集成测试。

## 数据流与映射

1. API `POST /api/result-generation/from-task-config-simple` 读取配置的已选表。
2. 若选择评分来源表，保持现有评分生成器；若选择最佳句子表，路由到 TTS 生成器；其余表返回现有风格的校验错误。
3. TTS 生成器读取 `word_clean_best_sentence` 中 `tts_status = 'pending'` 的行，按 `id` 排序。
4. 每个来源 `id` 只生成一条结果。既有生成结果从 JSON `bestSentenceId` 识别，重复调用跳过。
5. 结果正文保留来源 `id`、`wordCleanId`、词、释义、最佳英文句/中文翻译、评分及现有 TTS 元数据；正文携带任务类型 `word_clean_best_sentence_tts`、来源表和生成脚本路径。
6. 对 onboarding 验证调用，复用已有 `_meta.validationRunId` 注入与事务插入机制；验证标记由上层调用传入。

## 生成脚本

为任务配置写入现有约定目录下的 TTS 专属脚本。脚本仅解析任务正文并输出可供后续处理使用的结构化输入；它不连接业务库写数据、不调用 TTS。

## 错误处理

- 不含 `public.word_clean_best_sentence` 时拒绝生成。
- 来源读取异常转换为现有风格的 HTTP 500 错误。
- 无 pending 来源或全部已生成时返回 `insertedCount = 0`，不产生副作用。

## 测试

聚焦 Python 测试覆盖：表校验、pending 来源映射、按来源 ID 的去重、TTS 行正文及任务类型、API 路由分派。测试先失败，再写最小实现使其通过。

