# Task Plan

## Goal
在当前分支统一任务配置 1 与 4 的 TTS MinIO 成功语义；修复精确 10 条缺失最佳句子语音，同步精确 21,888 条已迁移但结果快照仍为本地 URL 的历史结果，并只读归属 276 个未引用对象。

## Current Phase
Final verification — 代码、真实数据修复、快照同步、对象归属和服务重启均已完成。

## Phases
1. [complete] 完成 TTS MinIO 端到端成功语义设计并确认数据边界
2. [complete] 编写详细实施计划，锁定测试、配置、快照和精确重试步骤
3. [complete] TDD 实现 Java/React 对象存储配置管理
4. [complete] TDD 实现 Python Worker MinIO 上传、验证、代理与 429 退避
5. [complete] TDD 接入 task_config_4 严格存储快照和端到端状态写回
6. [complete] 执行 Java、Python、React 全量验证并重启三个服务
7. [complete] 备份并精确补齐 210 条失败结果快照，以并发 1 重试和逐条验收
8. [complete] 全量 TTS 一致性审计、方案确认和详细实施计划
9. [complete] TDD 实现任务配置 1 的存储快照、三方校验和端到端 MinIO 成功语义
10. [complete] 自动测试、三服务重启与真实 MinIO 烟测
11. [complete] 备份并修复精确 10 条缺失最佳句子 TTS
12. [complete] 备份并同步精确 21,888 条任务配置 4 历史结果快照
13. [complete] 生成 276 个孤立对象归属报告并完成全量一致性验收

## Decisions
- 不使用独立 worktree；用户明确要求在当前分支直接修改。
- 复用当前 Docker MinIO `127.0.0.1:19100` 和 bucket `ai-file-navigation`，对象前缀为 `word_clean_tts`。
- MinIO 是处理器存储依赖，不是 CLI/AI Provider 调用通道。
- 对象存储凭据由 AI Task Center 自己的数据库配置管理，Worker 不读取相邻项目运行时配置。
- 新 TTS 不长期落本地文件。
- 仅精确选择任务配置 4、正式数据、当前 `FAILED`、来源表仍为 `public.word_clean_tts` 的 210 条结果进行快照补齐和重试。
- 对象上传后必须通过 `stat_object` 校验大小和 ETag/MD5，随后业务表回填成功，最终才允许写 `SUCCESS`。
- 210 条重试固定并发 1，429 使用 `Retry-After` 或有限指数退避。
- `handlerKey` 表示做什么；`executorType + executorId` 表示通过谁调用。
- `executorType` 第一版仅允许 `CLI`、`AI_PROVIDER`。
- 接入阶段的 `onboardingCliId` 与运行调用通道分离。
- 新字段迁移期允许为空；旧记录按自身字段、任务配置、旧 `cliId`/载荷顺序回退。
- 用户已明确授权把 21,888 条历史任务配置 4 成功结果的 `result_content` 同步为现有 MinIO 事实；不重新生成、不上传、不改变状态、运行、关联或时间字段。
- 精确 10 条缺失最佳句子 TTS 在完整备份后，以并发 1 调用 MiMo 重建并上传；其他来源记录不调用 MiMo。
- 276 个未引用 MinIO 对象只建立归属报告，不删除。
- Java 只负责编排和持久化；CLI、AI API、TTS 调用全部在 Python Worker。
- 失败重试不自动跨调用通道切换。

## Safety
- 不输出、记录或提交 API Key 正文。
- 实现和自动测试阶段不调用真实 AI/TTS；仅在全部测试和 MinIO 烟测通过后执行用户已授权的 210 条正式重试。
- 数据修改前先导出 210 条失败结果完整备份、精确 ID 清单并计算 SHA-256。
- 21,888 条同步仅允许更新已备份的 `result_content`；禁止删除已有结果、批次、业务记录、本地 WAV 或 MinIO 对象。
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
| Java 26 超出当前 Byte Buddy 正式支持版本，标准 `mvn test` 无法 mock | 1 | 先用单测确认 `net.bytebuddy.experimental` 可行，再将该属性限定到 Surefire 测试进程；标准命令 58/58 通过。 |
| 启动脚本以大结果集 `/api/task-run/list` 做 2 秒健康检查，误判并移除健康后端 | 1 | 改用轻量 `/api/object-storage-config` 探针；脚本语法与三服务真实启动均通过。 |
