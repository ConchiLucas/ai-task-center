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
6. Python Worker 只按批次保存的 `handlerKey + executorType + executorId` 快照查找处理器和模型调用通道；快照不完整时直接拒绝执行。
7. 任务结果和源数据库全部回填成功后，任务批次才更新为 `SUCCESS`。

## 配置边界

- 新建任务只填写名称、项目、数据库、来源表和描述，不在新建阶段选择任务类型或编码工具。
- 任务接入第一步选择唯一模型调用通道；CLI 与 AI Provider 都只负责返回模型内容，不负责写接入代码。
- 平台生成通用接入提示词，开发者可复制到任意外部编码工具；平台不保存也不追踪该工具。
- 外部代码以确定性键 `task_config_<任务配置ID>` 注册 Python 处理器；接入校验会检查处理器阶段能力与所选模型能力。
- 结果、批次和执行日志只使用 `handlerKey + executorType + executorId` 快照；执行、重试和批量提交都不能临时覆盖或按来源表推断。
- Java 后端负责编排与持久化；所有 CLI、LLM 和 TTS 外部调用都在 Python Worker 内完成。

## 任务接入生命周期

`新建基础任务 → 选择唯一模型通道 → 外部工具实现 task_config_<id> → Worker 注册校验 → 结果验证 → 批次验证 → READY → 严格快照执行`
