import json
import os
import shlex
import subprocess
from dataclasses import dataclass
from typing import Any

import psycopg2
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field


app = FastAPI(title="AI Task Center Python Worker")


class WorkerTaskRunItem(BaseModel):
    # 字段：任务执行记录 ID
    id: int
    # 字段：任务名称
    taskName: str
    # 字段：任务配置 ID
    taskConfigId: int | None = None
    # 字段：项目 ID
    projectId: int
    # 字段：数据库配置 ID
    databaseConfigId: int | None = None
    # 字段：选中的数据表 JSON
    selectedTables: str | None = None


class StartExecutionRequest(BaseModel):
    # 字段：本次执行选择的 CLI 配置 ID
    cliId: str
    # 字段：并发模式，thread 或 process
    executionMode: str = "thread"
    # 字段：线程或进程数量
    workerCount: int = Field(default=1, ge=1, le=32)
    # 字段：要执行的任务记录快照
    taskRuns: list[WorkerTaskRunItem]


@dataclass
class DatabaseSettings:
    # 字段：数据库 Host 地址
    host: str
    # 字段：数据库端口
    port: int
    # 字段：数据库名称
    name: str
    # 字段：数据库用户名
    user: str
    # 字段：数据库密码
    password: str


# 函数：load_database_settings
def load_database_settings() -> DatabaseSettings:
    return DatabaseSettings(
        host=os.getenv("TASK_CENTER_DB_HOST", "127.0.0.1"),
        port=int(os.getenv("TASK_CENTER_DB_PORT", "55432")),
        name=os.getenv("TASK_CENTER_DB_NAME", "ai_task_center"),
        user=os.getenv("TASK_CENTER_DB_USER", "conchi"),
        password=os.getenv("TASK_CENTER_DB_PASSWORD", "conchi123456"),
    )


# 函数：connect_database
def connect_database(settings: DatabaseSettings):
    return psycopg2.connect(
        host=settings.host,
        port=settings.port,
        dbname=settings.name,
        user=settings.user,
        password=settings.password,
        connect_timeout=5,
    )


# 函数：load_cli_config_payload
def load_cli_config_payload() -> dict[str, Any]:
    settings = load_database_settings()
    try:
        with connect_database(settings) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "select local_cli_config from tb_ai_config where config_key = %s order by id desc limit 1",
                    ("default",),
                )
                row = cursor.fetchone()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"读取 CLI 配置失败: {exc}") from exc

    if not row or not row[0]:
        return {"active": "", "configs": []}

    try:
        payload = json.loads(row[0])
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=500, detail=f"CLI 配置 JSON 解析失败: {exc}") from exc

    return payload if isinstance(payload, dict) else {"active": "", "configs": []}


# 函数：resolve_command
def resolve_command(command: str, working_directory: str | None) -> dict[str, Any]:
    if not command:
        return {"accessible": False, "resolvedPath": "", "message": "命令为空"}

    # 交互式 zsh 会加载用户的 alias/PATH，能兼容 antigravity 这类终端可用命令。
    shell_command = f"command -v {shlex.quote(command)}"
    try:
        result = subprocess.run(
            ["zsh", "-ic", shell_command],
            cwd=working_directory or None,
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
    except Exception as exc:
        return {"accessible": False, "resolvedPath": "", "message": str(exc)}

    resolved = result.stdout.strip()
    if result.returncode == 0 and resolved:
        return {"accessible": True, "resolvedPath": resolved, "message": "CLI 可访问"}

    message = result.stderr.strip() or "命令无法解析"
    return {"accessible": False, "resolvedPath": "", "message": message}


# 函数：normalize_cli_configs
def normalize_cli_configs(payload: dict[str, Any]) -> list[dict[str, Any]]:
    configs = payload.get("configs") if isinstance(payload, dict) else []
    active = payload.get("active") if isinstance(payload, dict) else ""
    if not isinstance(configs, list):
        return []

    normalized = []
    for item in configs:
        if not isinstance(item, dict):
            continue
        config = {
            "enabled": bool(item.get("enabled", True)),
            "id": str(item.get("id", "")),
            "label": str(item.get("label", "")),
            "command": str(item.get("command", "")),
            "defaultArgs": item.get("defaultArgs") if isinstance(item.get("defaultArgs"), list) else [],
            "workingDirectory": str(item.get("workingDirectory", "")),
            "timeoutSeconds": int(item.get("timeoutSeconds") or 300),
            "active": item.get("id") == active or bool(item.get("active", False)),
        }
        config["access"] = resolve_command(config["command"], config["workingDirectory"])
        normalized.append(config)
    return normalized


# 函数：find_cli_config
def find_cli_config(cli_id: str) -> dict[str, Any]:
    payload = load_cli_config_payload()
    for config in normalize_cli_configs(payload):
        if config.get("id") == cli_id:
            return config
    raise HTTPException(status_code=400, detail=f"CLI 配置不存在: {cli_id}")


# 函数：normalize_execution_mode
def normalize_execution_mode(mode: str) -> str:
    normalized = (mode or "thread").strip().lower()
    if normalized not in {"thread", "process"}:
        raise HTTPException(status_code=400, detail="executionMode 只支持 thread 或 process")
    return normalized


# 函数：health
@app.get("/api/health")
def health() -> dict[str, Any]:
    settings = load_database_settings()
    try:
        with connect_database(settings) as connection:
            with connection.cursor() as cursor:
                cursor.execute("select 1")
                cursor.fetchone()
    except Exception as exc:
        return {"status": "DOWN", "database": False, "message": str(exc)}
    return {"status": "UP", "database": True}


# 函数：get_cli_configs
@app.get("/api/cli/configs")
def get_cli_configs() -> dict[str, Any]:
    payload = load_cli_config_payload()
    configs = normalize_cli_configs(payload)
    return {
        "active": payload.get("active", ""),
        "count": len(configs),
        "configs": configs,
    }


# 函数：start_execution
@app.post("/api/execution/start")
def start_execution(request: StartExecutionRequest) -> dict[str, Any]:
    if not request.taskRuns:
        raise HTTPException(status_code=400, detail="任务列表不能为空")

    mode = normalize_execution_mode(request.executionMode)
    cli_config = find_cli_config(request.cliId)
    access = cli_config.get("access", {})
    if not access.get("accessible"):
        raise HTTPException(status_code=400, detail=f"CLI 不可访问: {access.get('message', '')}")

    task_ids = [item.id for item in request.taskRuns]
    return {
        "accepted": True,
        "message": "Python Worker 已接收执行请求",
        "cli": {
            "id": cli_config.get("id"),
            "label": cli_config.get("label"),
            "command": cli_config.get("command"),
            "resolvedPath": access.get("resolvedPath", ""),
        },
        "execution": {
            "mode": mode,
            "workerCount": request.workerCount,
            "taskCount": len(request.taskRuns),
            "taskRunIds": task_ids,
        },
    }


# 函数：start_execution_simple
@app.post("/api/execution/start-simple")
def start_execution_simple(
    cliId: str,
    executionMode: str = "thread",
    workerCount: int = Query(default=1, ge=1, le=32),
    taskRunIds: str = "",
) -> dict[str, Any]:
    mode = normalize_execution_mode(executionMode)
    cli_config = find_cli_config(cliId)
    access = cli_config.get("access", {})
    if not access.get("accessible"):
        raise HTTPException(status_code=400, detail=f"CLI 不可访问: {access.get('message', '')}")

    task_ids = [int(item) for item in taskRunIds.split(",") if item.strip().isdigit()]
    if not task_ids:
        raise HTTPException(status_code=400, detail="任务 ID 不能为空")

    return {
        "accepted": True,
        "message": "Python Worker 已接收执行请求",
        "cli": {
            "id": cli_config.get("id"),
            "label": cli_config.get("label"),
            "command": cli_config.get("command"),
            "resolvedPath": access.get("resolvedPath", ""),
        },
        "execution": {
            "mode": mode,
            "workerCount": workerCount,
            "taskCount": len(task_ids),
            "taskRunIds": task_ids,
        },
    }
