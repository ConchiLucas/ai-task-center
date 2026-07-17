# 统一任务处理器与调用通道设计

## 1. 背景与目标

AI Task Center 当前把 `cliId` 同时用于任务接入、任务运行、任务筛选和执行日志。这个模型无法准确表达已经存在的 TTS 链路：PostgreSQL 队列和 Python Worker 负责调度，而 Python Worker 在 TTS 任务中直接调用小米 MiMo API，运行时并不调用 CLI。

本设计将以下三个概念分离：

- 调度器：PostgreSQL 队列、租约、重试和 Python Worker 线程池。
- 任务处理器：描述任务“做什么”，例如句子评分或生成 TTS。
- 调用通道：描述任务“通过谁调用”，只能从本地 CLI 或 AI Provider 配置中选择一个。

目标是让现有评分和 TTS 任务显示、校验和执行一致，同时允许后续任务在 CLI 与 AI Provider API 之间选择，不重写队列系统。

## 2. 核心领域模型

### 2.1 任务处理器

任务配置新增 `handlerKey`，批次和执行日志保存相同快照。

第一批处理器：

| handlerKey | 说明 | 所需能力 |
| --- | --- | --- |
| `word_clean_sentence_score` | 对候选句评分并维护最佳句子 | `TEXT_GENERATION` |
| `word_clean_best_sentence_tts` | 为最佳句子生成音频 | `AUDIO_TTS` |

Python Worker 使用 `handlerKey` 选择业务处理器，不再把 `aiPromptJson.taskType` 作为主路由依据。旧数据仍可从现有批次载荷和已选表推导处理器，作为迁移期回退。

### 2.2 调用通道

任务运行通道由两个字段唯一确定：

- `executorType`：`CLI` 或 `AI_PROVIDER`。
- `executorId`：对应 `local_cli_config.configs[].id` 或 `providers.<id>`。

示例：

```json
{
  "handlerKey": "word_clean_best_sentence_tts",
  "executorType": "AI_PROVIDER",
  "executorId": "xiaomi-mimo-tts"
}
```

```json
{
  "handlerKey": "word_clean_sentence_score",
  "executorType": "CLI",
  "executorId": "codex"
}
```

CLI 和 AI Provider 都是被 Python Worker 调用的执行目标，不承担队列调度职责。

### 2.3 接入工具与运行通道分离

任务接入流程中的 Codex CLI 用于生成、验证或调整项目代码，属于开发期接入工具；任务正式运行时的 CLI 则是一类运行调用通道。两者不能继续复用 `cliId`。

迁移期定义：

- `onboardingCliId`：仅供任务接入流程使用。
- `executorType`、`executorId`：仅供验证和正式任务运行使用。
- 旧 `cliId` 暂时保留，作为历史数据兼容字段，不再作为新任务运行语义的唯一来源。

## 3. 调用通道配置

### 3.1 统一目录，保留现有存储

第一阶段不新建通用执行器配置表。后端从现有 `tb_ai_config.providers` 和 `local_cli_config` 生成统一的只读调用通道目录：

```json
{
  "type": "AI_PROVIDER",
  "id": "xiaomi-mimo-tts",
  "label": "小米 MiMo TTS",
  "protocol": "mimo-tts",
  "capabilities": ["AUDIO_TTS"],
  "enabled": true
}
```

统一目录通过独立 API 返回，任务配置页面不再自行拼接两套配置。

### 3.2 AI Provider 扩展

现有 AI Provider 仅允许 `openai-compatible` 和 `anthropic-compatible`，且保存时会丢弃 TTS 的音色等专用字段。Provider DTO 增加：

- `type`：作为协议标识，支持 `openai-compatible`、`anthropic-compatible`、`mimo-tts`。
- `capabilities`：能力列表。
- `voice`：TTS 常用默认音色。
- `options`：保存协议专用的非敏感扩展参数。
- `enabled`：是否允许被新任务选择。

API Key 只保存在 AI 配置中，不复制到任务、批次、日志或前端调用通道目录。

默认能力：

- `openai-compatible`、`anthropic-compatible`：`TEXT_GENERATION`。
- `mimo-tts`：`AUDIO_TTS`。

现有 ID 为 `xiaomi-mimo-tts` 或模型名包含 `tts` 的 Provider 在迁移读取时推导为 `mimo-tts`，保存后使用显式协议和能力。

### 3.3 CLI 扩展

本地 CLI 配置增加 `capabilities`，默认包含：

- `TEXT_GENERATION`
- `CODE_EXECUTION`

CLI 是否可访问仍由 Python Worker 检查，但工作目录必须限制在 AI Task Center 项目范围内。调用通道目录只返回启用且配置完整的 CLI。

## 4. 数据模型与兼容迁移

### 4.1 新字段

`tb_task_config`：

- `handler_key`
- `executor_type`
- `executor_id`
- `onboarding_cli_id`

`tb_task_result`：

- `handler_key`
- `executor_type`
- `executor_id`

`tb_task_run`：

- `handler_key`
- `executor_type`
- `executor_id`

`tb_task_execution_log`：

- `handler_key`
- `executor_type`
- `executor_id`
- `executor_label`

新字段在迁移期允许为空，避免更新或删除已有任务结果、批次和日志。新建记录必须填写新字段。

### 4.2 快照规则

生成任务结果时复制任务配置的处理器和调用通道；生成批次时再复制到 `tb_task_run`；每次执行时复制到 `tb_task_execution_log`。后续修改任务配置不改变历史运行记录。

执行日志只保存调用通道 ID、标签、协议、模型等非敏感元数据，禁止保存 API Key。

### 4.3 旧数据回退

迁移期读取顺序：

1. 优先读取记录自身的 `handlerKey`、`executorType`、`executorId`。
2. 缺失时读取关联任务配置的新字段。
3. 仍缺失时，根据旧 `cliId` 和批次载荷 `taskType` 推导。

旧 TTS 任务无论历史 `cliId` 为何，都推导为：

```text
handlerKey = word_clean_best_sentence_tts
executorType = AI_PROVIDER
executorId = xiaomi-mimo-tts
```

旧评分任务推导为：

```text
handlerKey = word_clean_sentence_score
executorType = CLI
executorId = 旧 cliId
```

本次迁移不批量更新、删除已有 `tb_task_result`、`tb_task_run` 或 `tb_task_run_result` 数据。

## 5. 后端职责

Java 后端负责：

- 提供统一调用通道目录。
- 保存任务配置时校验处理器、调用通道及能力是否匹配。
- 创建任务结果和批次时复制执行快照。
- 开始执行时只接收任务 ID、并发数等调度参数，不再要求选择 CLI。
- 向前端返回真实调用通道，而不是把 `cliId` 统一显示为执行工具。

Java 后端不直接调用 CLI、AI API 或 TTS API。

## 6. Python Worker 职责

Python Worker 内部分为两层注册表：

```text
TaskHandlerRegistry
├── word_clean_sentence_score
└── word_clean_best_sentence_tts

ExecutorRegistry
├── CLI
│   └── CliExecutor
└── AI_PROVIDER
    ├── OpenAICompatibleExecutor
    ├── AnthropicCompatibleExecutor
    └── MiMoTtsExecutor
```

队列领取后的执行顺序：

1. 读取批次快照。
2. 解析或兼容推导 `handlerKey`。
3. 通过 `executorType`、`executorId` 加载调用通道。
4. 校验调用通道能力满足处理器要求。
5. 调用任务处理器。
6. 任务处理器通过标准执行器接口调用 CLI 或 AI Provider。
7. 保存标准化响应、业务结果和非敏感执行元数据。

文本生成执行器返回统一结构，供评分处理器解析：

```json
{
  "rawOutput": "...",
  "executorType": "CLI",
  "executorId": "codex",
  "protocol": "codex-cli",
  "model": "...",
  "metadata": {}
}
```

TTS 执行器返回音频文件名、下载地址、格式、字节数、模型、音色和 Provider ID。Provider API 请求必须由 Python Worker 发起。

## 7. 前端交互

### 7.1 任务配置

任务配置表单增加：

- 任务处理器。
- 运行调用通道。
- 接入 CLI，仅在任务接入流程需要时展示。

调用通道下拉按类型分组，并根据处理器所需能力过滤：

- AI Provider：小米 MiMo TTS、OpenAI 等。
- 本地 CLI：Codex、Antigravity 等。

### 7.2 任务列表

- “执行工具”改为“调用通道”。
- TTS 显示 `AI API · 小米 MiMo TTS`。
- 评分任务按实际配置显示 `本地 CLI · Codex` 或 `AI API · <Provider>`。
- 筛选条件同时支持调用通道类型和具体调用通道。

### 7.3 开始执行

开始执行弹窗不再选择 CLI，只保留并发数。调用通道来自任务批次快照，避免同一批任务被临时改用不兼容的执行器。

## 8. 错误处理与重试

- 配置错误、能力不匹配、缺少 API Key：直接失败，不自动重试。
- 超时、限流、HTTP 429/502/503、CLI 被临时终止：沿用队列退避重试。
- 重试必须使用批次快照中的同一调用通道，不自动跨 CLI 和 AI Provider 切换，避免产生不可比较结果。
- TTS 单条失败不影响已成功音频，成功结果不重复调用 Provider。
- 日志明确区分调度失败、处理器失败和调用通道失败。

## 9. 测试策略

### Java

- 统一调用通道目录合并与密钥脱敏。
- 任务处理器和调用通道能力校验。
- 任务配置、结果、批次和执行日志快照复制。
- 旧 `cliId` 兼容推导。
- 开始执行接口不再强制 `cliId`。

### Python Worker

- CLI 文本生成执行器。
- OpenAI 兼容文本生成执行器。
- Anthropic 兼容文本生成执行器。
- MiMo TTS 执行器按 `executorId` 读取数据库配置。
- 处理器能力不匹配拒绝执行。
- TTS 和评分批次按 `handlerKey` 路由。
- 旧批次载荷兼容推导。
- API Key 不进入日志和响应。

### React

- 任务配置只展示能力匹配的调用通道。
- TTS 任务列表显示 MiMo API，不显示 Codex CLI。
- 开始执行无需选择 CLI。
- 调用通道筛选和历史兼容展示。

## 10. 实施顺序

1. 增加兼容字段和统一调用通道目录。
2. 增加 Java 领域校验与快照复制。
3. 重构 Python Worker 的处理器和执行器解析。
4. 让评分同时支持 CLI 与 AI Provider，TTS 使用配置指定的 Provider。
5. 更新任务配置、任务列表、日志和开始执行弹窗。
6. 运行聚焦测试、完整 Java/Python/前端测试和本地服务验证。
7. 保留旧字段回退至少一个稳定版本，再单独评估清理。

## 11. 非目标

- 不把 Java 后端改成 AI、CLI 或 TTS 调用方。
- 不引入 DAG 或通用工作流引擎。
- 不自动在失败后切换到另一调用通道。
- 不修改外部业务项目中的 AI/TTS 代码。
- 不批量改写或删除已有任务结果和历史批次。
- 不在本次改造中删除旧 `cliId`、`aiPromptJson` 或 `aiResponseJson` 字段。
