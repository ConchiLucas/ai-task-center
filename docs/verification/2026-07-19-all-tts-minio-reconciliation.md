# 全量 TTS MinIO 一致性修复验证

## 结论

任务配置 1（最佳句子 TTS）与任务配置 4（单个单词 TTS）现已统一为严格成功语义：MiMo 返回有效 WAV、MinIO 上传或复用并校验、正式来源回填成功后，任务结果才能成为 `SUCCESS`。

最终只读审计：

- `public.word_clean_tts`：22,098/22,098 `success`，22,098/22,098 引用有效 MinIO 对象。
- `public.word_clean_best_sentence`：22,098/22,098 `success`，22,098/22,098 引用有效 MinIO 对象。
- 两张业务表唯一引用对象：44,196。
- 缺失对象、数据库/对象大小差异、URL/key 差异：均为 0。
- task_config 1 精确修复集合：10/10 `SUCCESS`。
- task_config 4：22,098/22,098 结果快照为已验证 MinIO，旧本地下载 URL 为 0。
- MinIO `word_clean_tts/` 实际对象：44,472；当前未引用对象：276。
- 276 个未引用对象全部能关联历史 task_config 1 任务结果；删除对象数为 0。
- 终态队列：`QUEUED/RUNNING/RETRY_WAIT` 均为 0。

## 代码改造

- `python-worker/app/main.py`
  - 新最佳句子结果和批次携带严格 `storageTarget`。
  - 执行前校验结果、批次和实时 MinIO 配置三方一致。
  - 最佳句子 TTS 使用内存 WAV，不再通过旧包装函数长期写本地文件。
  - 执行顺序固定为 MiMo、MinIO 上传/验证、正式来源回填、结果成功。
  - 验证数据上传并验证 MinIO，但不回填来源表。
  - 失败记录 `SNAPSHOT_VALIDATION`、`MIMO_TTS`、`MINIO_UPLOAD`、`SOURCE_BACKFILL` 或 `RESULT_PERSISTENCE`。
  - 最佳句子批次 MiMo 并发固定为 1，成功重试清理过期错误字段。
- `python-worker/app/tts_reconciliation.py`
  - 精确 10 条集合守卫。
  - task_config 4 快照同步的纯转换和对象/来源一致性守卫。
  - 未引用对象的只读归属分类；没有删除、上传、MiMo 或数据库连接能力。

对应提交：

- `74a0c2b3` `feat: snapshot MinIO for best sentence TTS`
- `e9d1183f` `feat: validate best sentence storage snapshots`
- `02faaf44` `feat: verify best sentence TTS in MinIO`
- `65c0eb02` `feat: add guarded TTS reconciliation transforms`
- `33dacc80` `test: align TTS provider routing with MinIO`

## 精确 10 条修复

更新前完整备份目录：

`/private/tmp/ai-task-center-tts-reconciliation-20260719/best-sentence-10`

- 10 条完整任务结果。
- 10 条完整来源行。
- 10 条历史 run-result link。
- 10 个历史 run。
- 精确 ID 清单、本地缺失证据和 MinIO 缺失证据。
- `SHA256SUMS` 自身 SHA-256：`83f5da6c470f5aa103a0cc7a3aa836d12ecb395ae96b4d20090fae6711fea374`。
- `shasum -a 256 -c SHA256SUMS`：全部 `OK`。

历史数据额外发现：

- 3 条结果载荷保留旧拼写 `cando/loder/pleasureable`，来源当前为 `condo/loader/pleasurable`。
- 首轮执行中另有 3 条重音符号规范化差异；总计 6 条历史句子快照与当前来源不同。
- 来源 guard 正确阻止旧句子音频回填。未强行标成功；这 6 条刷新为当前真实来源并重新生成。

执行记录：

- run `8269`：首轮成功 2、失败 8；未自动重试。
- 首轮失败备份：`/private/tmp/ai-task-center-tts-reconciliation-20260719/best-sentence-8-retry`。
- 首轮失败备份清单 SHA-256：`410b1a311c60245f0d9872d40f3c4764929d26ff8bc93d7a8b763f6ade8d5c42`。
- run `8270`：仅重试失败 8 条，8/8 成功，并发 1。
- 最终逐条验收：结果 10、来源 10、MinIO 10、代理回读 10、本地确定性文件缺失 10。
- 验收证据：`/private/tmp/ai-task-center-tts-reconciliation-20260719/best-sentence-10/verification-after.json`。
- 验收证据 SHA-256：`0225bc8a184e1e5827babfc2f8d83f9de4ebbdae879e81e07a677bbdcc17abf5`。

## 21,888 条 task_config 4 快照同步

完整备份目录：

`/private/tmp/ai-task-center-tts-reconciliation-20260719/task-config-4-21888`

- 21,888 条完整任务结果 JSONL。
- 精确结果 ID、来源事实、MinIO 对象事实和非 `result_content` 字段哈希。
- 21,891 条完整 run-result link。
- 2,207 个完整相关 run。
- `SHA256SUMS` 自身 SHA-256：`d4900f5ba3f46aabaca0c7fece3e1f7a2d935e0d98cd1c76a181e22e40c706f8`。
- `shasum -a 256 -c SHA256SUMS`：全部 `OK`。

同步事务仅更新 `tb_task_result.result_content`：

- 更新数：21,888。
- MiMo 调用：0。
- MinIO 上传：0。
- 来源业务表更新：0。
- 结果状态、其余结果字段、link、run 均保持备份哈希不变。
- 同步后 task_config 4 MinIO 快照：22,098；旧本地 URL：0。
- 代理抽样结果 ID `89667`、`100611`、`111761` 均为有效 RIFF/WAVE。
- 验收证据：`/private/tmp/ai-task-center-tts-reconciliation-20260719/task-config-4-21888/verification-after.json`。
- 验收证据 SHA-256：`cafa2ffc273c9783440a310e9be32ffff5fdee5f893e1bed69f65c04d719e43e`。

## 未引用对象报告

- 报告路径：`/private/tmp/ai-task-center-tts-reconciliation-20260719/orphans-276.json`。
- 报告 SHA-256：`c21e13eeffb8fef37b134c77abf9108ccc1833cc4388aa2c295482185cdd3f79`。
- `historical_result`：276。
- `current_source_unreferenced`：0。
- `unmapped`：0。
- MinIO 删除调用：0。

最终审计：

- 路径：`/private/tmp/ai-task-center-tts-reconciliation-20260719/final-audit.json`。
- SHA-256：`d02d60281e482a336ae34cbb191c3ece86c9eba3e446aede86a0a0c343cb0a16`。

## 自动验证与服务

- Python：112/112 通过；`compileall` 通过。
- Java：58/58 通过。受限沙箱内 JDK 26 无法让 Mockito/Byte Buddy 自附加，按项目既有方式在宿主权限下重跑通过。
- React：3/3 聚焦测试通过；TypeScript 与 Vite 生产构建通过。
- `git diff --check` 与 `zsh -n scripts/start-dev.sh` 通过。
- 临时 WAV 烟测：上传、stat、大小、MD5/ETag、Worker 代理回读全部通过；唯一测试对象已删除。
- Java、Python Worker、React 已使用新代码重启；健康接口均通过。

已知非阻塞警告：来源数据库 `rob_english_word` 的 collation 版本为 2.41，而当前系统库提供 2.36。本次查询和事务均成功；未擅自执行数据库级 collation 重建。
