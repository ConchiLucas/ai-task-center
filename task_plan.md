# Task Plan

## Goal
在当前分支实现统一任务处理器与调用通道：PostgreSQL/Python Worker 负责调度，运行时从 CLI 或 AI Provider 中固定选择一个调用通道；TTS 显示并使用 MiMo API，评分任务可继续使用 CLI 并为直接 AI API 扩展留出标准接口。

## Current Phase
Complete — implementation, full verification, service restart, and read-only runtime acceptance are finished.

## Phases
1. [complete] 编写实施计划，锁定兼容字段、服务边界和测试命令
2. [complete] TDD 增加 Java 调用通道目录、能力模型和任务快照字段
3. [complete] TDD 重构 Python Worker 调用通道解析与 TTS/评分路由
4. [complete] TDD 更新批次生成、开始执行接口和历史兼容读取
5. [complete] 更新 React 任务配置、任务列表、日志和开始执行弹窗
6. [complete] 执行 Java、Python、React 全量验证并启动三个服务验收

## Decisions
- 不使用独立 worktree；用户明确要求在当前分支直接修改。
- `handlerKey` 表示做什么；`executorType + executorId` 表示通过谁调用。
- `executorType` 第一版仅允许 `CLI`、`AI_PROVIDER`。
- 接入阶段的 `onboardingCliId` 与运行调用通道分离。
- 新字段迁移期允许为空；旧记录按自身字段、任务配置、旧 `cliId`/载荷顺序回退。
- 不批量更新或删除已有 `tb_task_result`、`tb_task_run`、`tb_task_run_result`。
- Java 只负责编排和持久化；CLI、AI API、TTS 调用全部在 Python Worker。
- 失败重试不自动跨调用通道切换。

## Safety
- 不输出、记录或提交 API Key 正文。
- 不调用真实 AI 或 TTS 完成功能验证；使用单元测试和安全配置检查。
- 保留工作区中已有 MiMo、提示词边界和前端悬浮修复相关改动。
- 不修改外部业务项目。

## Errors Encountered
| Error | Attempt | Resolution |
| --- | --- | --- |
| `psql` 在当前环境不可用 | 1 | 使用已有安全配置验证结果和代码模型继续设计，不安装额外客户端。 |
| 实施计划使用了不存在的 `./mvnw` | 1 | 仓库没有 Maven Wrapper，后续统一使用系统 `mvn`。 |
| 沙箱内 Mockito/Byte Buddy 无法附加 GraalVM | 1 | 编译已通过；按既有项目验证方式在宿主权限下重跑同一 Maven 测试。 |
| 系统 Python 运行 Worker 测试出现 FastAPI/Starlette 参数不兼容 | 1 | 项目启动脚本明确使用 `python-worker/.venv`，后续测试统一用其解释器。 |
| zsh 提前展开 unittest 的 `test_*.py` 模式 | 1 | 对 `-p` 参数使用单引号后重跑，全量 34 项通过。 |
| Python Worker 虚拟环境未安装 `pytest` | 1 | 仅在项目 `.venv` 安装 pytest 8.4.2；全量 37 项通过。 |
| 全量 Python 测试新增调用通道校验后仍使用旧 mock | 1 | 补充统一目标解析 mock 与断言后通过，未放宽生产校验。 |
| 系统无 `psql` 命令 | 2 | 使用 Worker 已有 psycopg2 连接只读验证 Key 是否非空，未输出密钥。 |
