---
doc_id: ai-task-center-runtime-guide
title: AI Task Center 运行启动说明
doc_type: runtime
area: devops
tags: [startup, runtime, ports]
---

# AI Task Center 运行启动说明

## 一键启动

用户要求“启动前后端”“启动这个项目”“启动服务”时，优先执行：

```bash
./scripts/start-dev.sh
```

这个脚本会启动：

| 服务 | 地址 |
| --- | --- |
| React 前端 | `http://127.0.0.1:19637/` |
| Java 后端 | `http://127.0.0.1:18743` |
| Python Worker | `http://127.0.0.1:19186` |

## 数据库规则

- 使用本地已有 PostgreSQL：`127.0.0.1:5432`。
- 数据库：`ai_task_center`。
- 用户名：`conchi`。
- 密码：`conchi123456`。
- 不要为本项目执行 `docker compose up -d postgres`。

## 脚本行为

- 如果某个服务已经响应，脚本会跳过该服务。
- 如果 `web-react/node_modules` 不存在，脚本会执行 `npm install`。
- 如果 `python-worker/.venv` 不存在，脚本会创建虚拟环境并安装 `requirements.txt`。
- 日志写入 `.runtime/logs/`。
- PID 写入 `.runtime/pids/`。

## 健康检查

```bash
curl -s http://127.0.0.1:18743/api/task-run/list
curl -s http://127.0.0.1:19186/api/health
curl -s http://127.0.0.1:19186/api/queue/status
curl -s -o /tmp/ai-task-center-frontend.check -w '%{http_code}' http://127.0.0.1:19637/
```

## 常用环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `TASK_CENTER_DB_HOST` | `127.0.0.1` | PostgreSQL Host |
| `TASK_CENTER_DB_PORT` | `5432` | PostgreSQL 端口 |
| `TASK_CENTER_DB_NAME` | `ai_task_center` | PostgreSQL 数据库 |
| `TASK_CENTER_DB_USER` | `conchi` | PostgreSQL 用户名 |
| `TASK_CENTER_DB_PASSWORD` | `conchi123456` | PostgreSQL 密码 |
| `TASK_CENTER_SERVER_PORT` | `18743` | Java 后端端口 |
| `TASK_CENTER_FRONTEND_PORT` | `19637` | React 前端端口 |
| `PYTHON_WORKER_PORT` | `19186` | Python Worker 端口 |
| `TASK_QUEUE_MAX_WORKERS` | `8` | PostgreSQL 队列全局最大并发线程数 |
| `TASK_QUEUE_POLL_SECONDS` | `1` | Worker 轮询队列间隔秒数 |
| `TASK_QUEUE_LEASE_SECONDS` | `900` | 单次领取任务的租约秒数 |
| `TASK_QUEUE_HEARTBEAT_SECONDS` | `30` | Worker 续租和过期恢复检查间隔 |

## 队列规则

- PostgreSQL 是任务队列和业务状态的唯一事实来源，不依赖 Redis。
- `QUEUED` 和 `RETRY_WAIT` 任务由 Python Worker 自动领取。
- 同一批提交的实际并发数取页面填写值与 `TASK_QUEUE_MAX_WORKERS` 中的较小值。
- Worker 异常退出后，租约到期任务会自动进入重试等待。
- 临时错误最多自动执行三次，部分成功批次不会自动重复调用 AI。
