# TTS MinIO 端到端成功语义设计

## 目标

把基础单词 TTS 的 `SUCCESS` 收紧为端到端成功：小米 MiMo 生成音频、AI Task Center 的 Python Worker 上传现有 Docker MinIO、校验对象、回填业务表和任务结果全部完成后，任务结果才允许进入 `SUCCESS`。

本次使用现有 MinIO，不启动新实例：

- endpoint：现有 Docker MinIO（当前为 `127.0.0.1:19100`）；
- bucket：`ai-file-navigation`；
- object key：`word_clean_tts/<原文件名>`；
- 业务 URL：`/ai-file-navigation/word_clean_tts/<原文件名>`。

## 范围与不变边界

### 本次处理

- 当前任务配置 4 的 210 条 `FAILED` 正式结果：重新调用 MiMo 生成 WAV，再由 Python Worker 直接上传 MinIO。
- 后续任务配置 4 新生成的基础单词 TTS：默认走相同的 MinIO 端到端链路。
- 增加 AI Task Center 自己的对象存储配置管理，不依赖相邻项目运行时配置。
- 任务中心详情页继续能够播放新生成的 MinIO 音频。

### 明确不处理

- 现有 21,888 条 `SUCCESS` 结果不迁移、不删除、不改 URL，继续引用本地 WAV。
- 不启动第二个 MinIO，不修改相邻业务项目源码。
- 不把 MinIO 当成 AI/CLI 运行调用通道；MinIO 是任务处理器的存储依赖。
- 不把仅收到 MiMo 音频或仅写入本地临时文件视为成功。

## 配置模型

新增 `tb_object_storage_config`，由 Java 后端和 React 配置管理页面维护。首个配置复用现有 Docker MinIO 的连接信息。建议字段：

- `config_name`
- `provider_type`，第一版只支持 `MINIO`
- `endpoint`
- `access_key`
- `secret_key`
- `use_ssl`
- `bucket_name`
- `base_path`
- `enabled`
- `is_default`

列表和详情接口不得返回明文 `secret_key`；更新时空密码表示保留旧值。系统只能有一个启用的默认对象存储配置。

Python Worker 从 AI Task Center 数据库读取指定配置，不读取其他仓库的 `.env`。部署时只进行一次受控复制，把现有 Docker MinIO 连接信息登记到新表；运行时不再依赖复制来源。

## 确定性存储快照

任务配置 4 的新结果载荷增加：

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

批次构建必须把同一快照写入 `ai_prompt_json`。执行时严格校验结果载荷、批次载荷和实时配置一致，不允许根据环境变量或其他项目配置静默回退。

现有 210 条失败结果缺少该快照。部署时先备份这 210 条完整结果 JSON 和 ID，再仅对 `task_config_id = 4`、`record_type = FORMAL`、`status = FAILED` 且来源仍为 `public.word_clean_tts` 的精确集合补入当前存储快照。不得修改 21,888 条成功结果。

## Python Worker 数据流

单条任务严格按以下顺序执行：

1. 校验任务处理器、模型目标和对象存储快照。
2. 调用小米 MiMo，获得 WAV 字节。
3. 验证音频非空且具有有效 RIFF/WAVE 文件头。
4. 计算字节数和 MD5。
5. Python Worker 使用 MinIO SDK 上传到确定性 object key。
6. 使用 `stat_object` 重新读取对象，校验对象存在、大小一致和 ETag/MD5 一致。
7. 回填 `public.word_clean_tts`：bucket、object key、object URL、文件大小、格式和生成时间。
8. 将相同 MinIO 元数据写入 `tb_task_result.result_content.ttsResult`。
9. 最后才把 `tb_task_result.status` 更新为 `SUCCESS`。

新链路不长期保存本地 WAV。可使用内存字节流或受控临时文件，上传完成或失败后都清理临时文件。现有 21,888 个本地文件不受影响。

## 幂等性和失败语义

object key 固定为 `word_clean_tts/<安全文件名>`。重试时：

- 对象存在且大小和 MD5 一致：直接复用；
- 对象不存在：上传并校验；
- 对象存在但内容不一致：重新上传并再次校验；
- MinIO 上传成功但业务回填失败：任务保持 `FAILED`，同一 key 留作下次幂等重试；
- MiMo、MinIO、对象校验、业务回填任一步失败：任务保持 `FAILED` 并记录具体阶段；
- 不允许出现 MinIO 对象未验证却将结果写为 `SUCCESS` 的路径。

对 MiMo HTTP 429 采用全局低并发和退避：优先读取 `Retry-After`，否则使用有限指数退避；达到最大次数仍失败则保留 `FAILED`，不得无限循环。

## 播放与展示

业务表保存相对 MinIO URL：

```text
/ai-file-navigation/word_clean_tts/<文件名>
```

任务中心不能依赖业务 Go 服务代理。Python Worker 新增只读 MinIO 音频代理，通过存储配置读取对象并流式返回。新 `ttsResult.downloadUrl` 指向 Worker 代理，`objectUrl` 保留业务相对 URL。React 详情页优先使用 `downloadUrl`，并可展示 bucket 和 object key。

列表仍把 `SUCCESS` 显示为“处理成功”，但新链路下该状态已经代表 MiMo、MinIO、校验和回填全部成功。历史 21,888 条是明确保留的旧语义数据，不进行追溯迁移。

## 210 条失败结果的重试

实现和验证完成后：

1. 导出 210 条失败结果的完整备份、数据库 ID 和 SHA-256。
2. 精确补入对象存储快照。
3. 创建只包含这 210 条失败结果的新批次。
4. 使用并发 1 执行，MiMo 429 按退避策略重试。
5. 每条成功后独立验证任务结果、业务表和 MinIO 对象。
6. 最终报告成功、失败、MinIO 缺失、大小不符和字段不符数量。

本步骤会产生最多 210 次实际 MiMo TTS 调用和 210 次 MinIO 上传。不得包含 21,888 条已有成功结果。

## 测试与验收

### 自动测试

- Java：对象存储配置创建、更新、默认唯一、密码掩码和校验。
- React：配置表单必填、密码保留语义和生产构建。
- Python：配置读取、快照严格校验、上传成功、已有对象复用、大小/MD5 不符、MinIO 失败、业务回填失败、429 退避和最终状态。
- 回归：旧 21,888 条结果不进入迁移或重试选择集合。

### 运行态验收

- 新 Worker 健康且队列调度器 `running=true`。
- 使用专用测试 key 完成一次 MinIO 上传、读取、校验和清理。
- 210 条批次执行后逐条核对：
  - `tb_task_result.status = SUCCESS`；
  - `word_clean_tts.status = success`；
  - bucket/key/URL 非空且一致；
  - MinIO 对象存在、大小和 MD5 一致；
  - Worker 音频代理能返回 `audio/wav`。
- 21,888 条历史成功结果的数据库内容、本地文件数量和哈希不变。

## 数据安全

- 开发测试阶段不得误执行正式 TTS 批次。
- 210 条正式重试必须在代码和 MinIO 集成验证完成后单独执行。
- 所有数据库更新都必须限定任务配置、记录类型、状态和精确 ID 集合。
- 不删除已有任务结果、任务批次、业务记录或 21,888 个本地 WAV。
- 不在日志、API 响应、测试快照或提交记录中输出 MinIO secret key。
