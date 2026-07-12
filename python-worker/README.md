# AI Task Center Python Worker

Python Worker 负责 CLI、大模型、脚本执行和 PostgreSQL 可靠任务队列：

- 直连 `ai_task_center` PostgreSQL
- 读取 `tb_ai_config.local_cli_config`
- 校验已配置的本地 CLI 是否可被当前机器访问
- 使用 `FOR UPDATE SKIP LOCKED` 原子领取任务
- 使用线程池并发启动独立 CLI 子进程
- 使用租约、心跳、领取令牌和自动重试恢复异常任务
- 成功后批量回填任务结果和源数据库

## 启动

```bash
cd python-worker
uvicorn app.main:app --host 0.0.0.0 --port 19186
```

## 环境变量

```bash
TASK_CENTER_DB_HOST=127.0.0.1
TASK_CENTER_DB_PORT=5432
TASK_CENTER_DB_NAME=ai_task_center
TASK_CENTER_DB_USER=conchi
TASK_CENTER_DB_PASSWORD=conchi123456
TASK_QUEUE_MAX_WORKERS=8
TASK_QUEUE_POLL_SECONDS=1
TASK_QUEUE_LEASE_SECONDS=900
TASK_QUEUE_HEARTBEAT_SECONDS=30
```

## 接口

- `GET /api/health`
- `GET /api/queue/status`
- `GET /api/cli/configs`
