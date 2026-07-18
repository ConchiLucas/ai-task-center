# Findings

## 统一调用通道改造

- 当前 `cliId` 同时存在于 `TaskConfig`、`TaskResult`、`TaskRun`、`TaskExecutionLog`，并被接入流程、单条处理、批次生成、筛选和日志复用。
- TTS Worker 处理器已经显式 `del cli_id` 并直接调用 MiMo，说明页面显示 Codex 是数据模型失真，不是实际调用链。
- `AiConfigService` 当前只允许 `openai-compatible` 和 `anthropic-compatible`，Provider DTO 不保存 `voice`、能力或扩展参数；若继续保存 MiMo 配置会丢失 TTS 专用字段。
- `TaskConfigService.generateRunBatches` 仍强制从请求或任务配置取得 CLI，并用原生 SQL 写入 `tb_task_run.cli_id`。
- `TaskRunService.startExecution` 强制要求开始执行请求携带 CLI，并会覆盖批次的 `cliId`，不适合固定 Provider 的 TTS 批次。
- `TaskResultService` 也有单条与批量 CLI 入口，需要和任务批次一起兼容，不能只改任务列表页面。
- 第一阶段应保留现有 AI Provider 与 CLI JSON 存储，通过后端生成统一只读调用通道目录，避免引入新通用配置表。
- 任务接入 CLI 与运行调用通道必须分离，否则 AI Provider 运行任务仍无法使用 Codex 完成接入代码准备。
- Java 兼容解析规则已落地：显式快照优先；旧 TTS 根据表/载荷推导到 `AI_PROVIDER/xiaomi-mimo-tts`；旧评分保留原 CLI。
- 新 JPA 字段均未设置非空约束，应用启动只需增列，不需要批量更新受保护历史数据。
- Python 的 `TaskConfigSnapshot`、`TaskResultSnapshot`、`TaskRunSnapshot` 和三组 SQL 仍只读取 `cli_id`；新字段应追加为带默认值的 dataclass 字段，避免破坏现有测试构造器。
- TTS 项目处理目前直接调用 `generate_mimo_tts(payload["ttsInput"])`；必须从批次快照向下传递 `executor_id` 才能支持多个 TTS Provider。
- Python 评分路径仍直接依赖 `find_cli_config/run_cli_prompt`，统一文本适配器完成后需要在单条、批次和队列路径逐步替换。
- 评分结果构造函数把传输元数据命名为 `cliResponse/cliConfig/effectiveCliId`，直接替换会波及批量回填载荷；迁移应先把标准执行结果转换为兼容形状，再逐步重命名字段。
- 当前验证评分测试已覆盖“不回写来源库”，适合扩展一条 AI Provider 路径测试，确认直接 Provider 调用不会触发 `find_cli_config/run_cli_prompt`。
- Python 统一文本适配器已能输出一致的 `rawOutput/executorType/executorId/protocol/model/metadata`；旧评分载荷暂时保留 `cliId` 兼容字段，但同时写入新的 executor 元数据。
- TaskConfig/TaskResult/TaskRun 的 Python 快照 SQL 已读取新字段；dataclass 新字段均有默认值，旧测试和旧记录仍可回退。
- 新生成评分/TTS 结果会写入 handler/executor 快照；未运行真实结果生成，因此没有新增、更新或删除业务数据。
- Java `TaskResultService.process/processBatch` 仍强制 CLI，但 Python 单条端点已允许 `cliId` 为空；Java Client 可省略或发送空值而由 Worker 使用结果快照。
- Python 批量验证端点仍把 `cliId` 声明为必填，需改成可选，批量内部再由各结果快照解析调用通道。
- Java 由正式结果临时组装异步批次时仍按调用方 CLI 分组且未复制 handler/executor，需按任务结果快照分组并写入运行/日志快照。
- `TaskResultServiceListTest` 的新增 RED 用例已确认旧校验边界：Provider 单条执行报“任务结果未配置执行 CLI”，Provider 批量执行报“请选择执行 CLI”；应只对旧 CLI 路径保留该校验。
- 结果转队列时必须把 handler/executor 纳入分组键，否则不同调用通道的正式结果可能被合并进同一 TaskRun，导致 Worker 使用错误的调用目标。
- React 原先在任务配置、任务列表、运行列表、日志和开始弹窗中都直接展示或要求 `cliId`；本次已统一改为运行调用通道，并仅在旧结果批量兼容入口保留可选 CLI 覆盖。
- Ant Design 表格 `ellipsis` 的默认呈现不保证自定义 render 内容有完整提示；显式 `Tooltip` 已用于最近结果、执行结果、本次结果和本次错误。

- 截图显示任务日志 `#6056 生成 TTS 任务 - 批次 4483`，状态“处理失败”，任务结果 5 条。
- 最近结果与执行结果列都被单行省略；可读部分为“批次执行完成：成功 0 条，失败 5 条；部分成功任务不自动重复调用 AI”。
- 用户希望悬浮在被省略内容上时展示完整文本。
- 页面代码位于 `web-react/src/App.tsx` 的 `renderTaskRunLogView`。
- 顶部“最近结果”由 CSS `.task-run-log-summary-item .ant-typography` 强制单行省略，但当前没有 Tooltip。
- 执行记录表“执行结果”列使用 Ant Design `ellipsis: true`，自定义 `render` 返回普通文本，同样没有显式悬浮全文。
- 展开后的批次明细还有“本次结果”和“本次错误”两列使用 `ellipsis: true`，也属于用户所说的看不到内容，应统一处理。
- 执行行展开后已有完整的运行日志、请求载荷、响应结果和逐条明细，适合保留为详细诊断入口。
- 批次 6056 的 5 条结果（89659–89663）全部在约 0.24 秒内失败，错误完全一致：`502: word-agent TTS 请求失败: {"detail":"缺少 MIMO_API_KEY 或 WORD_AGENT_MIMO_API_KEY"}`。
- Worker 调用 `http://127.0.0.1:8010/v1/tts/generate`；word-agent 进程在 8010 响应请求，但其 TTS 提供商凭据未配置，因此不是例句数据、并发或 Codex CLI 导致的失败。
- Worker 按 `continueOnItemFailure` 契约逐条记录失败，并把批次汇总为成功 0 / 失败 5；这解释了页面上的“部分成功任务不自动重复调用 AI”文案。
- 该 TTS 批次的规则 `callCli=false`，实际不会调用 Codex CLI；页面“执行工具 Codex CLI”只是批次配置标签，容易造成误解，但不是本次范围的主因。
- “TTS 单条验证执行只支持当前验证数据”由 Worker `process_tts_validation_task_result` 主动返回：TTS 单条接口只允许 `VALIDATION_CURRENT`，但最近失败的 89659–89663 均是 `FORMAL`。
- 任务配置 1 已处于 `READY/COMPLETED`，当前结果分布为 22,415 条 `FORMAL` 与 3 条 `VALIDATION_HISTORY`，没有 `VALIDATION_CURRENT`；因此当前页面上任何 TTS 结果都不应该走单条验证接口。
- word-agent 进程 PID 32283 自 2026-06-22 启动，工作目录为其项目根目录；`.env` 修改时间为 2026-07-04，晚于进程启动。
- 当前 `.env` 经 Settings 安全加载确认 `mimoKeyPresent=True`，且 base URL/model/voice 正常；运行进程仍返回“缺少 key”，说明它缓存的是 `.env` 更新前的空配置。现有证据不是 key 内容无效，而是服务未重启加载新配置。
- AI Task Center 的数据库配置位于 `tb_ai_config.providers` JSON；每个 provider 包含 `id`, `label`, `type`, `base_url`, `api_key`, `model`, `max_tokens`。
- Java 的公开 providers 接口会把 `apiKey` 置空，适合前端展示但不适合 word-agent 取密钥；若跨服务读取，需使用数据库只读访问或新增受控内部接口。
- Python Worker 已有从 `ai_task_center.tb_ai_config` 读取 `local_cli_config` 的模式，可作为数据库配置读取的项目内参考。
- 最终运行模型已落地：`handlerKey` 决定任务处理器，`executorType + executorId` 决定实际调用通道，`onboardingCliId` 仅供接入代码阶段使用。
- Python Worker 是唯一对外执行边界：TTS、CLI 文本生成、OpenAI-compatible、Anthropic-compatible 均从 Worker 适配器发起；Java 不直接调用这些外部能力。
- 显式执行目标严格优先于旧 `cliId`；显式 MiMo Provider 只接受数据库中的非空 Key，不会因配置缺失静默改用环境变量。
- 旧记录通过结果/批次关联的任务配置及历史字段进行只读兼容解析，不需要批量改写已有结果、批次或 link。
- 正式 TTS 单条处理已改为创建 PostgreSQL 队列批次；只有当前验证数据保留直接验证入口，因此不会再出现 FORMAL 结果误走验证接口。
- 任务列表筛选和展示均使用统一调用通道；历史 TTS 即使旧记录仍有 `cliId=codex`，也会按来源表推断并显示/筛选为小米 MiMo TTS。
- `GET /api/ai/config` 及保存响应均脱敏；前端以空 Key 回传保存时，后端保留数据库中的既有 Key，避免编辑非密钥字段时意外清空凭据。

## 2026-07-18 最终架构结论

- 旧的“任务处理器 + 运行调用通道 + 接入代码 CLI”三选项把业务实现工具和运行模型混在了一起；最终表单不再暴露这三项。
- 任务处理器是 Python 代码注册项，模型调用通道是运行依赖，两者分别由 `handlerKey` 与 `executorType + executorId` 表达。
- CLI（例如 Codex/Antigravity）和 AI Provider 在运行架构中地位相同：都只提供模型返回，不参与编写或部署任务代码。
- 接入代码由用户把平台提示词复制到任意外部编码工具完成；平台只验证 Worker 是否注册 `task_config_<id>`，不记录所用编码工具。
- 历史兼容已明确取消：领域模型、Java 请求、Worker 快照和前端接口均不再携带 `cliId`；保留的数据库旧列只用于无损迁移并已放宽非空约束。
- 执行边界是严格快照：缺失处理器、缺失模型目标、未知处理器、阶段能力缺失或模型能力不匹配都会明确失败，不再静默回退。
