---
doc_id: ai-task-center-database-info
title: AI Task Center 数据库连接信息
doc_type: database
area: backend
tags: [postgresql, database]
---

# AI Task Center 数据库连接信息

## 默认连接

```text
host: 127.0.0.1
port: 5432
database: ai_task_center
user: conchi
password: conchi123456
```

Java JDBC 默认连接：

```text
jdbc:postgresql://localhost:5432/ai_task_center
```

## 规则

- 使用本地已有 PostgreSQL 服务。
- 不要为本项目新启动 PostgreSQL Docker 容器。
- Java 后端通过 `src/main/resources/application.yml` 读取数据库配置。
- Python Worker 通过环境变量读取数据库配置，默认值与 Java 后端保持一致。

## 关键表

| 表 | 说明 |
| --- | --- |
| `tb_interface_project` | 项目配置 |
| `tb_connection` | 数据库配置 |
| `tb_ai_config` | AI 配置，包含本地 CLI 配置 |
| `tb_task_config` | 任务配置 |
| `tb_task_run` | 任务列表/任务执行记录 |
| `tb_task_result` | 任务结果 |
