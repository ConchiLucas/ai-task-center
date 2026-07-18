# Progress

- 2026-07-17：用户批准统一任务处理器与调用通道设计，设计文档已提交为 `d727f21`。
- 2026-07-17：开始当前分支实施准备；已确认 Java 模型、批次生成、开始执行、单条结果和 Python Worker 均存在 `cliId` 耦合。
- 2026-07-17：确定采用新增兼容字段而非批量改写历史数据，实施计划编写中。
- 2026-07-17：实施计划已保存到 `docs/superpowers/plans/2026-07-17-unified-execution-target.md` 并通过占位符、类型命名和规格覆盖自检；选择当前会话内联执行。
- 2026-07-17：Task 1 已添加统一调用通道目录的 RED 测试；首次测试命令发现仓库无 `mvnw`，已切换为 `mvn`。
- 2026-07-17：Task 1 生产代码已编译；沙箱测试运行被 GraalVM 下 Mockito/Byte Buddy 自附加限制阻断，需在宿主权限下复验。
- 2026-07-17：Task 1 宿主环境聚焦测试通过（1/1）；统一目录仅返回非敏感元数据。
- 2026-07-17：Task 2 按 RED/GREEN 完成，处理器与调用通道兼容解析测试 4/4 通过；四个领域模型已增加 nullable 快照字段，未更新历史记录。
- 2026-07-17：Java 配置能力校验、批次快照、无 CLI 启动、新建任务和执行日志快照均按 RED/GREEN 完成；最近聚焦测试 11/11 与 6/6 通过。
- 2026-07-17：Python Task 4 已添加调用通道解析 RED 测试；系统 Python 依赖组合不兼容，按启动脚本切换到 `python-worker/.venv/bin/python`。
- 2026-07-17：Python 调用通道解析、MiMo Provider ID、CLI/OpenAI/Anthropic 文本适配器、handler 路由、TTS/评分接线和结果快照均按 RED/GREEN 完成；全量 Worker 测试 34/34 通过。
- 2026-07-17：TaskResult Provider 单条/批量执行新增 RED 测试，宿主测试 8 项中 2 项按预期失败，确认 Java 仍强制要求 CLI；开始修复兼容传播与队列快照。
- 2026-07-17：TaskResult Java 兼容传播与正式结果队列快照完成；Provider 可无 CLI 执行，旧 CLI 校验保留，聚焦测试 9/9 通过；Python 批量验证端点已允许可选 cliId。
- 2026-07-17：React 任务配置已拆分任务处理器、运行调用通道和接入代码 CLI；任务/结果/日志展示真实调用通道，开始执行不再覆盖目标，省略结果均支持悬浮全文；生产构建通过。
- 2026-07-17：Java 全量测试 34/34、Python Worker 全量测试 34/34、Python 语法检查和 git diff 格式检查均通过；进入三服务启动与只读接口验收。
- 2026-07-17：根据独立代码审查修复显式 CLI 快照被接入 CLI 覆盖、显式 MiMo Provider 环境变量回退、旧记录任务配置回退、未知 handler、正式 TTS 单条入队和统一调用通道筛选。
- 2026-07-17：最终 Java 全量测试 41/41、Python Worker 全量测试 37/37、React 生产构建、Python 语法、启动脚本语法和 `git diff --check` 全部通过。
- 2026-07-17：已重启 Java、React、Python Worker；三端健康，队列 `QUEUED/RUNNING/RETRY_WAIT` 均为 0，未调用真实 AI/TTS。
- 2026-07-17：只读确认 `xiaomi-mimo-tts` 目录协议为 `mimo-tts`、能力为 `AUDIO_TTS`，数据库 Key 非空且公开配置接口不返回 Key。

- 2026-07-17：开始只读定位任务日志页面与批次 4483 的失败链路；尚未修改业务代码或数据。
- 2026-07-17：定位到三个需要悬浮全文的省略位置：顶部最近结果、执行结果、本次结果/错误。
- 2026-07-17：只读确认失败根因是 word-agent 缺少 `MIMO_API_KEY` / `WORD_AGENT_MIMO_API_KEY`，5 条结果均为同一 502 错误。
- 2026-07-17：进一步确认单条执行错误来自 FORMAL 结果误走验证接口；当前 `.env` 已有 MIMO key，但 word-agent 进程早于配置文件启动，需重启才会加载。
- 2026-07-17：用户确认真实 MiMo Key 维护在数据库；开始定位配置表与 word-agent 数据库读取边界。
- 2026-07-17：确认数据库 provider JSON 已有通用 API Key 字段，公开接口会脱敏；继续核对实际 MiMo provider。
# 2026-07-17 MiMo 数据库配置读取

- 已确认 `tb_ai_config.providers` 中 `xiaomi-mimo-tts` 的 Key 存在，数据库实际字段为 `api_key`、`base_url`。
- 已在 word-agent 当前 `main` 分支实现数据库优先、环境变量回退的配置解析器。
- 已通过 13 个单元/接口测试和本次变更文件 Ruff 检查。
- 已安全验证解析来源为 `database`，未调用真实 TTS，未输出 Key。
- 已重启 word-agent，健康接口返回 `status=ok`。

# 2026-07-18 任务接入与严格执行快照

- 新建任务已简化为基础业务信息，生命周期从 `TARGET_SELECTION` 开始。
- 模型通道选择已移动到接入流程；通用提示词支持任意外部编码工具，平台不再保存“接入代码 CLI”。
- Python Worker 已增加处理器注册表、处理器描述接口和处理器批次输入构建接口。
- `CODE_READY` 会校验确定性处理器键 `task_config_<id>`、阶段回调以及所选模型能力。
- Java、Worker 和 React 已移除执行阶段的 `cliId` 覆盖、旧字段回退、按表名/载荷推断和旧执行接收接口。
- 结果、批次、队列领取和执行日志统一只依赖 `handlerKey + executorType + executorId`。
- Java 全量测试、Python Worker 50 个测试和 React 生产构建已通过；未执行真实任务、AI 或 TTS。
