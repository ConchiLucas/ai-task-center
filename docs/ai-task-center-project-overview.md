---
doc_id: ai-task-center-project-overview
title: AI Task Center 项目总览
doc_type: overview
area: project
tags: [overview, java, react, python-worker]
---

# AI Task Center 项目总览

## 项目定位

AI Task Center 是一个任务中心后台，用于维护配置管理、任务配置、任务列表和任务结果。任务配置用 `handlerKey` 描述业务处理器，用 `executorType + executorId` 固定选择一个 CLI 或 AI Provider 调用通道；Python Worker 统一承接 CLI、LLM API 和 TTS API 交互。

## 技术栈

| 层 | 技术 | 默认端口 |
| --- | --- | --- |
| 前端 | React + Ant Design + Vite | `19637` |
| Java 后端 | Spring Boot + Spring Data JPA | `18743` |
| Python Worker | FastAPI + Uvicorn + psycopg2 | `19186` |
| 数据库 | 本地已有 PostgreSQL 16 | `5432` |

## 主要目录

| 路径 | 说明 |
| --- | --- |
| `src/main/java` | Java 后端源码 |
| `src/main/resources/application.yml` | Java 后端端口、数据库和 Python Worker 地址配置 |
| `web-react` | React + Ant Design 前端 |
| `python-worker` | Python Worker 服务 |
| `scripts/start-dev.sh` | 一键启动脚本 |
| `docs` | AI 入口索引关联的二层文档 |

## 服务链路

1. 前端页面调用 Java 后端 API。
2. Java 后端读写 `ai_task_center` PostgreSQL。
3. 任务列表点击“开始执行”后，Java 将任务原子更新为 `QUEUED` 并立即返回。
4. Python Worker 使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 多线程领取任务。
5. Worker 通过租约、心跳和领取令牌避免重复完成，并对临时故障自动重试。
6. Python Worker 根据任务快照从 PostgreSQL 读取一个调用通道：`CLI` 可选择 Codex 等本地工具，`AI_PROVIDER` 可选择 OpenAI/Anthropic 兼容接口或 MiMo TTS。
7. 任务结果和源数据库全部回填成功后，任务批次才更新为 `SUCCESS`。

## 配置边界

- “任务处理器”决定做什么，例如单词例句评分或最佳例句 TTS。
- “运行调用通道”决定通过哪个 CLI 或 AI API 执行；创建结果/批次后会保存快照，开始执行和重试不会自动换通道。
- “接入代码 CLI”只用于生成、验证任务接入代码，不参与运行时 Provider 选择。
- Java 后端负责编排与持久化；所有 CLI、LLM 和 TTS 外部调用都在 Python Worker 内完成。
