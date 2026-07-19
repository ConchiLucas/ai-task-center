# 全量 TTS MinIO 一致性修复设计

## 目标

让 AI Task Center 中两条 TTS 业务链路都满足同一成功语义：MiMo 返回有效 WAV、Python Worker 上传到既定 MinIO、对象大小和 MD5/ETag 校验通过、来源业务表回填成功、任务结果持久化成功后，结果才允许标记为 `SUCCESS`。

本次同时修复已经审计出的数据差异，但不删除任何任务结果、批次、关联记录、业务记录、本地文件或 MinIO 对象。

## 已确认基线

### 单个单词 TTS

- 来源表：`public.word_clean_tts`。
- 当前记录：22,098 条，全部为 `success`。
- MinIO 引用：22,098 条，实际对象存在 22,098 个。
- 数据库 URL、对象大小与 MinIO 一致，无缺失对象。
- task_config 4 的 210 条新结果快照已记录 MinIO；此前 21,888 条结果快照仍保存旧 Worker 本地 URL，但业务表和物理对象已经迁移到 MinIO。

### 最佳句子 TTS

- 来源表：`public.word_clean_best_sentence`。
- 当前记录：22,098 条，其中 22,088 条 `success`、9 条 `pending`、1 条 `running`。
- 有 10 条来源记录没有 MinIO 引用，确定性对象名在 MinIO 中也不存在，本地 WAV 同样不存在。
- 这 10 条对应 task_config 1 的结果中，3 条为 `FAILED`，7 条为错误的 `SUCCESS`。

精确修复集合：

| best_sentence_id | word | 当前来源状态 | task_result_id | 当前结果状态 |
| ---: | --- | --- | ---: | --- |
| 160 | acceleration | pending | 67373 | FAILED |
| 1308 | assailant | running | 68521 | SUCCESS |
| 2738 | condo | pending | 69951 | SUCCESS |
| 3413 | cliche | pending | 70626 | SUCCESS |
| 3708 | communique | pending | 70921 | SUCCESS |
| 10662 | loader | pending | 77650 | SUCCESS |
| 11885 | multiplicand | pending | 78873 | FAILED |
| 13912 | pleasurable | pending | 80900 | SUCCESS |
| 15230 | recherche | pending | 82218 | SUCCESS |
| 18747 | tectonic | pending | 85735 | FAILED |

### 未引用对象

- `ai-file-navigation/word_clean_tts/` 当前共有 44,462 个对象。
- 两张当前业务表共引用 44,186 个对象。
- 276 个对象没有被当前业务表引用，绝大部分符合历史最佳句子对象命名。
- 本次只建立归属报告并保留对象，不执行删除。

## 方案

### 1. 统一最佳句子 TTS 执行链路

task_config 1 不再调用会长期写入 `python-worker/data/tts_audio` 的旧包装函数。执行改为：

1. 从任务结果读取确定性的来源快照、MiMo 执行目标和 `storageTarget`。
2. Python Worker 从数据库读取 MiMo Key，不依赖相邻项目配置。
3. MiMo 返回音频字节后验证 RIFF/WAVE 文件头。
4. 上传到 `ai-file-navigation/word_clean_tts/<fileName>`。
5. 使用 MinIO `stat_object` 校验大小和 ETag/MD5；相同对象允许安全复用，不同内容覆盖后重新校验。
6. 正式数据回填 `public.word_clean_best_sentence`；验证数据不回填来源表。
7. 最后写入任务结果的 `ttsResult`、`execution` 和 `backfillResult`，并清理旧失败字段。

任一步失败时结果保持 `FAILED`，并记录 `failureStage`：`SNAPSHOT_VALIDATION`、`MIMO_TTS`、`MINIO_UPLOAD`、`SOURCE_BACKFILL` 或 `RESULT_PERSISTENCE`。

### 2. 存储快照

新生成的最佳句子 TTS 结果必须包含：

```json
{
  "storageTarget": {
    "storageConfigId": 1,
    "providerType": "MINIO",
    "bucket": "ai-file-navigation",
    "objectPrefix": "word_clean_tts"
  }
}
```

批次提示词必须复制相同快照。执行时任务结果、批次和实时配置三方必须一致；配置被停用或字段不一致时拒绝执行，不静默切换存储或模型。

### 3. 精确修复 10 条最佳句子 TTS

正式写入前执行以下保护：

1. 重新查询并断言上述 10 个 task_result ID、best_sentence ID、来源状态和对象缺失状态完全一致。
2. 导出 10 条完整任务结果、10 条完整来源行、相关批次关联和 ID 清单，生成 SHA-256 清单。
3. 在单个事务中：
   - 为 10 条结果补入 MinIO `storageTarget`；
   - 将 7 条假成功结果改为可执行失败状态，并写明“来源和 MinIO 校验不一致”；
   - 将 best_sentence 1308 的残留 `running` 恢复为 `pending`；
   - 断言更新集合严格等于这 10 条。
4. 通过项目现有批量执行接口创建一个只包含这 10 条结果的正式批次，并发固定为 1。
5. 因 10 个本地文件和 MinIO 对象均不存在，允许对这 10 条调用 MiMo 重新生成；其他记录不调用 MiMo。
6. 监控到终态并逐条验证结果、来源回填、MinIO WAV、大小、MD5/ETag和 Worker 代理。

部分失败不会覆盖其他成功项，也不会自动切换 Provider。

### 4. 同步 task_config 4 的 21,888 条旧结果快照

该步骤不生成音频、不上传对象，只把已经存在并经业务表引用的 MinIO 事实同步到 AI Task Center 结果快照。

1. 精确选择 task_config 4、正式 `SUCCESS`、仍使用 Worker 本地下载 URL、且能通过 `wordCleanTtsId` 一对一连接 `public.word_clean_tts` 的 21,888 条结果。
2. 完整导出更新前结果和规范化 SHA-256。
3. 从业务表读取 bucket、key、URL、大小，从 MinIO list/stat 读取 ETag；断言 21,888 个对象全部存在且大小一致。
4. 在一个事务中仅更新 `result_content` 的 `storageTarget` 和 `ttsResult` 存储字段：
   - `bucket`、`objectKey`、`objectUrl`；
   - Worker MinIO 代理 `downloadUrl`；
   - `fileSize/byteSize`、`md5/etag`、`storageVerified=true`。
5. 不修改结果状态、来源业务表、task run、run-result link、创建时间或完成时间。
6. 更新后复算完整集合并逐项抽查代理播放。

这是用户对先前“保留旧结果快照”约束的明确变更；物理对象本身不做迁移或覆盖。

### 5. 未引用对象归属报告

276 个未引用对象按确定性文件名与 task_config 1 历史结果的 `bestSentenceId/wordCleanId` 做连接，输出：

- 能关联历史任务结果但当前来源行已不存在的对象；
- 能关联当前来源行但来源未引用的对象；
- 无法关联任何任务结果或业务行的对象。

报告只读生成，不调用 MinIO 删除接口。

## 状态和界面语义

- `SUCCESS`：必须有已验证的 MinIO 对象；正式数据还必须完成来源表回填。
- `FAILED`：显示 `failureStage` 和当前错误，不保留过期的旧成功/失败字段。
- 结果详情使用 Worker 只读代理播放 MinIO 音频，不暴露 MinIO Secret Key。
- 历史 21,888 条同步后，任务结果页面与业务表展示同一个 MinIO 对象事实。

## 测试策略

按 TDD 实施：

- RED：最佳句子成功路径必须要求存储快照、MinIO 校验和来源回填。
- RED：上传失败、对象大小/ETag 不一致、来源 guard 变化时必须失败并标记正确阶段。
- RED：验证数据上传 MinIO 但不得写来源表。
- RED：成功重试会清除旧 `processorError/failureStage`。
- RED：21,888 条快照同步函数拒绝缺对象、大小不一致、重复来源 ID或非目标结果。
- GREEN 后运行 Python 全量测试、Java 全量测试、React 测试与构建、compileall 和 `git diff --check`。
- 真实数据修复前完成 MinIO 临时对象烟测；数据修复后执行全量只读一致性审计。

## 安全与回滚

- 不输出或提交 MiMo/MinIO Secret Key。
- 不删除任何数据库记录、批次、关联或 MinIO 对象。
- 每个数据阶段都有独立备份目录、完整 JSON/ID 清单和 SHA-256。
- 10 条修复与 21,888 条快照同步分成两个事务和两个验收阶段，任一阶段失败不影响另一阶段。
- 代码修改先通过自动测试并重启服务，再执行真实 MiMo 调用。
- 快照同步可由备份的完整 `result_content` 精确回滚；10 条正式执行产生的音频不自动删除，避免破坏已完成结果。

## 验收标准

- `public.word_clean_tts`：22,098/22,098 MinIO 完整。
- `public.word_clean_best_sentence`：22,098/22,098 `success` 且 MinIO 完整。
- 两张当前业务表：44,196 个唯一引用对象，缺失、大小不一致、URL不一致均为 0。
- task_config 1：上述 10 条最终全部 `SUCCESS`，来源和 MinIO 一致。
- task_config 4：22,098 条结果快照全部记录 MinIO，旧本地下载 URL 数量为 0。
- 未引用对象报告完成，MinIO 删除数量为 0。
