---
doc_id: ai-task-center-project-overview
title: AI Task Center 项目总览
doc_type: overview
area: project
tags: [overview, java, react, python-worker]
---

# AI Task Center 项目总览

## 项目定位

AI Task Center 是一个任务中心后台，用于维护配置管理、任务配置、任务列表和任务结果。后续任务执行会基于配置选择项目、数据库、数据表和本地 CLI，然后由 Python Worker 承接 CLI 或大模型交互。

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
6. Python Worker 从 PostgreSQL 读取 AI 配置，选择 `codex` 或 `antigravity` CLI 执行。
7. 任务结果和源数据库全部回填成功后，任务批次才更新为 `SUCCESS`。
