---
doc_id: ai-task-center-agents-md
title: AI 入口索引: ai-task-center
doc_type: agent_index
area: agent
tags: [agent, index, startup]
---

# AGENTS.md

## 使用方式

1. AI 先读取本文件。
2. 需要启动项目时，优先读取 [运行启动说明](./docs/ai-task-center-runtime-guide.md)，然后执行 `./scripts/start-dev.sh`。
3. 需要了解项目边界、技术栈和目录结构时，读取 [项目总览](./docs/ai-task-center-project-overview.md)。
4. 需要连接数据库、确认库名账号端口或排查数据库问题时，读取 [数据库连接信息](./docs/ai-task-center-database-info.md)。
5. 源码、配置、表结构、日志和临时实现细节可以直接读取项目目录。

## 启动识别规则

- 用户说“启动前后端”“启动这个项目”“启动服务”时，默认执行 `./scripts/start-dev.sh`。
- 启动脚本会同时处理 Java 后端、React 前端和 Python Worker。
- PostgreSQL 使用本地已有服务 `127.0.0.1:5432`，不要为本项目新启动 Docker PostgreSQL。
- 启动后优先检查：
  - 前端：`http://127.0.0.1:19637/`
  - Java 后端：`http://127.0.0.1:18743/api/task-run/list`
  - Python Worker：`http://127.0.0.1:19186/api/health`

## 第二层文档

| 文档 | 什么时候读 | 命令 |
| --- | --- | --- |
| [项目总览](./docs/ai-task-center-project-overview.md) | 需要了解技术栈、目录结构、功能边界和服务链路 | `sed -n '1,220p' docs/ai-task-center-project-overview.md` |
| [运行启动说明](./docs/ai-task-center-runtime-guide.md) | 需要启动前端、Java 后端、Python Worker 或排查端口 | `sed -n '1,260p' docs/ai-task-center-runtime-guide.md` |
| [数据库连接信息](./docs/ai-task-center-database-info.md) | 需要连接 PostgreSQL、确认库名账号端口或排查数据库配置 | `sed -n '1,220p' docs/ai-task-center-database-info.md` |
| [rob 任务生成规则](./docs/ai-task-center-rob-english-word-task-rules.md) | 需要处理 rob_english_word_workforce 的评分任务、TTS 任务或判断哪些表能生成任务 | `sed -n '1,220p' docs/ai-task-center-rob-english-word-task-rules.md` |

## 目录层级规则

- 第一层：项目根目录 `AGENTS.md`。
- 第二层：`docs/*.md`。
- 第三层：`docs/<second-layer-doc-id>/*.md`，后续更多层级继续用文件夹表达。
