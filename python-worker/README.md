# AI Task Center Python Worker

Python Worker 负责后续 CLI、大模型和脚本执行相关能力。当前第一版只做一件事：

- 直连 `ai_task_center` PostgreSQL
- 读取 `tb_ai_config.local_cli_config`
- 校验已配置的本地 CLI 是否可被当前机器访问

## 启动

```bash
cd python-worker
uvicorn app.main:app --host 0.0.0.0 --port 19186
```

## 环境变量

```bash
TASK_CENTER_DB_HOST=127.0.0.1
TASK_CENTER_DB_PORT=55432
TASK_CENTER_DB_NAME=ai_task_center
TASK_CENTER_DB_USER=conchi
TASK_CENTER_DB_PASSWORD=conchi123456
```

## 接口

- `GET /api/health`
- `GET /api/cli/configs`
