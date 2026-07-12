import json
import logging
import os
import shlex
import signal
import socket
import subprocess
import threading
import time
import uuid
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg2
from psycopg2.extras import execute_values
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field

from app.result_generation_idempotency import (
    build_recovered_generation_response,
    merge_onboarding_generation_metadata,
    normalize_onboarding_generation_id,
)


app = FastAPI(title="AI Task Center Python Worker")
PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCORE_SOURCE_DESCRIPTION = "word_clean_sentence_score_generation"
PROMPT_DIGIT_TRANSLATION = str.maketrans({
    "0": "零",
    "1": "一",
    "2": "二",
    "3": "三",
    "4": "四",
    "5": "五",
    "6": "六",
    "7": "七",
    "8": "八",
    "9": "九",
})
SCORE_MIN = 1
SCORE_MAX = 100
LOGGER = logging.getLogger("ai-task-center.queue")
QUEUE_MAX_WORKERS = max(1, min(8, int(os.getenv("TASK_QUEUE_MAX_WORKERS", "8"))))
QUEUE_POLL_SECONDS = max(0.2, float(os.getenv("TASK_QUEUE_POLL_SECONDS", "1")))
QUEUE_LEASE_SECONDS = max(120, int(os.getenv("TASK_QUEUE_LEASE_SECONDS", "900")))
QUEUE_HEARTBEAT_SECONDS = max(10, int(os.getenv("TASK_QUEUE_HEARTBEAT_SECONDS", "30")))
QUEUE_WORKER_ID = f"{socket.gethostname()}:{os.getpid()}:{uuid.uuid4().hex[:8]}"
QUEUE_STOP_EVENT = threading.Event()
QUEUE_STATE_LOCK = threading.Lock()
QUEUE_STATE: dict[str, Any] = {
    "running": False,
    "workerId": QUEUE_WORKER_ID,
    "maxWorkers": QUEUE_MAX_WORKERS,
    "activeCount": 0,
    "lastError": "",
}
QUEUE_SCHEDULER_THREAD: threading.Thread | None = None


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
class TaskConfigSnapshot:
    # 字段：任务配置 ID
    id: int
    # 字段：任务配置名称
    task_name: str
    # 字段：所属项目 ID
    project_id: int
    # 字段：默认执行 CLI 配置 ID
    cli_id: str
    # 字段：关联数据库配置 ID
    database_config_id: int
    # 字段：任务关联的数据表 JSON
    selected_tables: str


@dataclass
class ConnectionConfigSnapshot:
    # 字段：数据库配置 ID
    id: int
    # 字段：数据库类型
    connection_type: str
    # 字段：数据库 Host 地址
    connection_url: str
    # 字段：数据库名称
    database_name: str
    # 字段：数据库端口
    port: int
    # 字段：数据库用户名
    db_login_name: str
    # 字段：数据库密码
    db_login_password: str


@dataclass
class TaskResultSnapshot:
    # 字段：任务结果 ID
    id: int
    # 字段：任务结果名称
    result_name: str
    # 字段：关联任务配置 ID
    task_config_id: int | None
    # 字段：所属项目 ID
    project_id: int
    # 字段：执行 CLI 配置 ID
    cli_id: str
    # 字段：结果来源数据库配置 ID
    database_config_id: int
    # 字段：任务结果状态
    status: str
    # 字段：任务结果正文 JSON
    result_content: str


@dataclass
class TaskRunSnapshot:
    # 字段：任务执行批次 ID
    id: int
    # 字段：任务执行批次名称
    task_name: str
    # 字段：执行 CLI 配置 ID
    cli_id: str
    # 字段：批次级发送给 AI 的 JSON 提示词
    ai_prompt_json: str
    # 字段：批次级 AI 原始响应 JSON
    ai_response_json: str


@dataclass(frozen=True)
class QueuedTaskClaim:
    # 字段：领取的任务批次 ID
    id: int
    # 字段：执行使用的 CLI 配置 ID
    cli_id: str
    # 字段：本次执行序号
    attempt_no: int
    # 字段：允许执行的最大序号
    max_attempts: int
    # 字段：本次领取任务的唯一令牌
    claim_token: str
    # 字段：提交时选择的并发数量
    requested_worker_count: int
    # 字段：提交时选择的执行模式
    execution_mode: str
    # 字段：同一次批量提交的调度组 ID
    dispatch_group_id: str


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
        port=int(os.getenv("TASK_CENTER_DB_PORT", "5432")),
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


# 函数：queue_retry_delay_seconds
def queue_retry_delay_seconds(attempt_no: int) -> int:
    delays = (30, 120, 600)
    return delays[min(max(attempt_no - 1, 0), len(delays) - 1)]


# 函数：queue_error_message
def queue_error_message(error: Exception) -> str:
    if isinstance(error, HTTPException):
        return str(error.detail)
    return str(error)


# 函数：is_retryable_queue_error
def is_retryable_queue_error(message: str) -> bool:
    lowered = message.lower()
    retryable_markers = (
        "超时",
        "timeout",
        "timed out",
        "connection",
        "连接",
        "temporar",
        "rate limit",
        "429",
        "502",
        "503",
        "进程被信号",
        "killed",
        "cli 返回失败",
        "json 解析失败",
        "没有返回内容",
    )
    return any(marker in lowered for marker in retryable_markers)


# 函数：set_queue_runtime_state
def set_queue_runtime_state(**values: Any) -> None:
    with QUEUE_STATE_LOCK:
        QUEUE_STATE.update(values)


# 函数：queue_runtime_state
def queue_runtime_state() -> dict[str, Any]:
    with QUEUE_STATE_LOCK:
        return dict(QUEUE_STATE)


# 函数：claim_next_queued_task
def claim_next_queued_task() -> QueuedTaskClaim | None:
    settings = load_database_settings()
    claim_token = uuid.uuid4().hex
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                with candidate as (
                    select task.id
                    from tb_task_run task
                    where task.status in ('QUEUED', 'RETRY_WAIT')
                      and (task.next_retry_at is null or task.next_retry_at <= now())
                      and coalesce(task.attempt_no, 0) < coalesce(task.max_attempts, 3)
                      and (
                          coalesce(task.dispatch_group_id, '') = ''
                          or (
                              select count(*)
                              from tb_task_run active
                              where active.dispatch_group_id = task.dispatch_group_id
                                and active.status = 'RUNNING'
                          ) < greatest(1, least(32, coalesce(task.requested_worker_count, 1)))
                      )
                    order by task.next_retry_at nulls first, task.created_at, task.id
                    for update of task skip locked
                    limit 1
                )
                update tb_task_run task
                set status = 'RUNNING',
                    attempt_no = coalesce(task.attempt_no, 0) + 1,
                    claim_token = %s,
                    worker_id = %s,
                    heartbeat_at = now(),
                    lease_until = now() + (%s * interval '1 second'),
                    start_time = now(),
                    end_time = null,
                    duration_seconds = null,
                    next_retry_at = null,
                    reason = 'Python Worker 已领取任务',
                    run_log = 'Python Worker 已领取第 ' || (coalesce(task.attempt_no, 0) + 1) || ' 次执行。',
                    updated_at = now()
                from candidate
                where task.id = candidate.id
                returning task.id,
                          task.cli_id,
                          task.attempt_no,
                          coalesce(task.max_attempts, 3),
                          task.claim_token,
                          greatest(1, least(32, coalesce(task.requested_worker_count, 1))),
                          coalesce(task.execution_mode, 'thread'),
                          coalesce(task.dispatch_group_id, '')
                """,
                (claim_token, QUEUE_WORKER_ID, QUEUE_LEASE_SECONDS),
            )
            row = cursor.fetchone()
            if not row:
                connection.commit()
                return None
            cursor.execute(
                """
                insert into tb_task_execution_log (
                    created_at, updated_at, task_run_id, attempt_no, cli_id,
                    execution_mode, worker_count, status, start_time, reason,
                    run_log, ai_prompt_json, ai_response_json
                )
                select now(), now(), task.id, task.attempt_no, task.cli_id,
                       coalesce(task.execution_mode, 'thread'),
                       greatest(1, least(32, coalesce(task.requested_worker_count, 1))),
                       'RUNNING', now(), 'Python Worker 已领取任务',
                       'Python Worker ' || %s || ' 已领取第 ' || task.attempt_no || ' 次执行。',
                       task.ai_prompt_json, ''
                from tb_task_run task
                where task.id = %s
                on conflict (task_run_id, attempt_no) do update
                set updated_at = now(),
                    cli_id = excluded.cli_id,
                    execution_mode = excluded.execution_mode,
                    worker_count = excluded.worker_count,
                    status = 'RUNNING',
                    start_time = now(),
                    end_time = null,
                    duration_seconds = null,
                    reason = excluded.reason,
                    run_log = excluded.run_log,
                    ai_prompt_json = excluded.ai_prompt_json,
                    ai_response_json = ''
                """,
                (QUEUE_WORKER_ID, int(row[0])),
            )
        connection.commit()
    return QueuedTaskClaim(
        id=int(row[0]),
        cli_id=str(row[1] or ""),
        attempt_no=int(row[2]),
        max_attempts=int(row[3]),
        claim_token=str(row[4]),
        requested_worker_count=int(row[5]),
        execution_mode=str(row[6]),
        dispatch_group_id=str(row[7]),
    )


# 函数：renew_queued_task_lease
def renew_queued_task_lease(claim: QueuedTaskClaim) -> bool:
    settings = load_database_settings()
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                update tb_task_run
                set heartbeat_at = now(),
                    lease_until = now() + (%s * interval '1 second'),
                    updated_at = now()
                where id = %s and status = 'RUNNING' and claim_token = %s
                """,
                (QUEUE_LEASE_SECONDS, claim.id, claim.claim_token),
            )
            renewed = cursor.rowcount == 1
        connection.commit()
    return renewed


# 函数：sync_task_run_result_links
def sync_task_run_result_links(task_run_id: int) -> None:
    settings = load_database_settings()
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                update tb_task_run_result link
                set status = result.status,
                    error_message = coalesce(result.error_message, ''),
                    updated_at = now()
                from tb_task_result result
                where link.task_run_id = %s
                  and link.task_result_id = result.id
                """,
                (task_run_id,),
            )
        connection.commit()


# 函数：finish_queued_task
def finish_queued_task(
    claim: QueuedTaskClaim,
    status: str,
    reason: str,
    retry_delay_seconds: int = 0,
) -> bool:
    settings = load_database_settings()
    terminal = status in {"SUCCESS", "FAILED", "CANCELLED"}
    execution_status = "SUCCESS" if status == "SUCCESS" else "FAILED"
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                update tb_task_run
                set status = %s,
                    reason = %s,
                    run_log = %s,
                    end_time = case when %s then now() else null end,
                    duration_seconds = case
                        when %s and start_time is not null
                        then greatest(0, extract(epoch from (now() - start_time))::integer)
                        else null
                    end,
                    next_retry_at = case
                        when %s = 'RETRY_WAIT' then now() + (%s * interval '1 second')
                        else null
                    end,
                    lease_until = null,
                    heartbeat_at = null,
                    claim_token = null,
                    worker_id = null,
                    updated_at = now()
                where id = %s and status = 'RUNNING' and claim_token = %s
                """,
                (
                    status,
                    reason[:1000],
                    reason[:4000],
                    terminal,
                    terminal,
                    status,
                    retry_delay_seconds,
                    claim.id,
                    claim.claim_token,
                ),
            )
            updated = cursor.rowcount == 1
            if updated:
                cursor.execute(
                    """
                    update tb_task_execution_log
                    set status = %s,
                        end_time = now(),
                        duration_seconds = case
                            when start_time is not null
                            then greatest(0, extract(epoch from (now() - start_time))::integer)
                            else 0
                        end,
                        reason = %s,
                        run_log = %s,
                        ai_response_json = coalesce((
                            select ai_response_json from tb_task_run where id = %s
                        ), ''),
                        updated_at = now()
                    where task_run_id = %s and attempt_no = %s
                    """,
                    (execution_status, reason[:1000], reason[:4000], claim.id, claim.id, claim.attempt_no),
                )
        connection.commit()
    if updated:
        sync_task_run_result_links(claim.id)
    return updated


# 函数：recover_expired_queued_tasks
def recover_expired_queued_tasks() -> int:
    settings = load_database_settings()
    recovered = 0
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                select id, coalesce(attempt_no, 0), coalesce(max_attempts, 3)
                from tb_task_run
                where status = 'RUNNING' and lease_until < now()
                order by lease_until
                for update skip locked
                """
            )
            for task_run_id, attempt_no, max_attempts in cursor.fetchall():
                can_retry = int(attempt_no) < int(max_attempts)
                next_status = "RETRY_WAIT" if can_retry else "FAILED"
                reason = "Worker 租约过期，等待自动重试" if can_retry else "Worker 租约过期且已达到最大重试次数"
                retry_delay = queue_retry_delay_seconds(int(attempt_no)) if can_retry else 0
                cursor.execute(
                    """
                    update tb_task_run
                    set status = %s,
                        reason = %s,
                        next_retry_at = case
                            when %s = 'RETRY_WAIT' then now() + (%s * interval '1 second')
                            else null
                        end,
                        lease_until = null,
                        heartbeat_at = null,
                        claim_token = null,
                        worker_id = null,
                        updated_at = now()
                    where id = %s and status = 'RUNNING'
                    """,
                    (next_status, reason, next_status, retry_delay, task_run_id),
                )
                cursor.execute(
                    """
                    update tb_task_execution_log
                    set status = 'FAILED', end_time = now(), reason = %s,
                        run_log = %s, updated_at = now()
                    where task_run_id = %s and attempt_no = %s
                    """,
                    (reason, reason, task_run_id, attempt_no),
                )
                recovered += 1
        connection.commit()
    return recovered


# 函数：execute_queued_task
def execute_queued_task(claim: QueuedTaskClaim) -> None:
    try:
        response = process_word_clean_sentence_task_run_batch(claim.id, claim.cli_id)
        failed_count = int(response.get("failedCount") or 0)
        success_count = int(response.get("successCount") or 0)
        if failed_count > 0:
            finish_queued_task(
                claim,
                "FAILED",
                f"批次执行完成：成功 {success_count} 条，失败 {failed_count} 条；部分成功任务不自动重复调用 AI",
            )
            return
        finish_queued_task(claim, "SUCCESS", f"批次执行成功，共完成 {success_count} 条任务结果")
    except Exception as error:
        message = queue_error_message(error)
        can_retry = claim.attempt_no < claim.max_attempts and is_retryable_queue_error(message)
        if can_retry:
            retry_delay = queue_retry_delay_seconds(claim.attempt_no)
            finish_queued_task(
                claim,
                "RETRY_WAIT",
                f"第 {claim.attempt_no} 次执行失败，{retry_delay} 秒后自动重试：{message}",
                retry_delay,
            )
        else:
            finish_queued_task(claim, "FAILED", f"第 {claim.attempt_no} 次执行失败：{message}")


# 函数：run_queue_scheduler
def run_queue_scheduler() -> None:
    active: dict[Future[None], QueuedTaskClaim] = {}
    executor = ThreadPoolExecutor(max_workers=QUEUE_MAX_WORKERS, thread_name_prefix="task-queue")
    last_heartbeat_at = 0.0
    last_recovery_at = 0.0
    set_queue_runtime_state(running=True, lastError="")
    try:
        while not QUEUE_STOP_EVENT.is_set():
            for future in [item for item in active if item.done()]:
                claim = active.pop(future)
                try:
                    future.result()
                except Exception as error:
                    LOGGER.exception("任务 %s 的队列执行线程异常: %s", claim.id, error)

            now = time.monotonic()
            if now - last_heartbeat_at >= QUEUE_HEARTBEAT_SECONDS:
                for claim in active.values():
                    try:
                        renew_queued_task_lease(claim)
                    except Exception as error:
                        LOGGER.warning("任务 %s 续租失败: %s", claim.id, error)
                last_heartbeat_at = now

            if now - last_recovery_at >= QUEUE_HEARTBEAT_SECONDS:
                try:
                    recover_expired_queued_tasks()
                except Exception as error:
                    LOGGER.warning("恢复过期任务失败: %s", error)
                last_recovery_at = now

            while len(active) < QUEUE_MAX_WORKERS and not QUEUE_STOP_EVENT.is_set():
                try:
                    claim = claim_next_queued_task()
                except Exception as error:
                    set_queue_runtime_state(lastError=str(error))
                    LOGGER.warning("领取队列任务失败: %s", error)
                    break
                if claim is None:
                    set_queue_runtime_state(lastError="")
                    break
                active[executor.submit(execute_queued_task, claim)] = claim
                set_queue_runtime_state(lastError="")

            set_queue_runtime_state(activeCount=len(active))
            QUEUE_STOP_EVENT.wait(QUEUE_POLL_SECONDS)
    finally:
        set_queue_runtime_state(running=False, activeCount=len(active))
        executor.shutdown(wait=False, cancel_futures=False)


# 函数：start_queue_scheduler
@app.on_event("startup")
def start_queue_scheduler() -> None:
    global QUEUE_SCHEDULER_THREAD
    if QUEUE_SCHEDULER_THREAD and QUEUE_SCHEDULER_THREAD.is_alive():
        return
    QUEUE_STOP_EVENT.clear()
    QUEUE_SCHEDULER_THREAD = threading.Thread(
        target=run_queue_scheduler,
        name="postgres-task-queue",
        daemon=True,
    )
    QUEUE_SCHEDULER_THREAD.start()


# 函数：stop_queue_scheduler
@app.on_event("shutdown")
def stop_queue_scheduler() -> None:
    QUEUE_STOP_EVENT.set()
    if QUEUE_SCHEDULER_THREAD:
        QUEUE_SCHEDULER_THREAD.join(timeout=5)


# 函数：connect_source_database
def connect_source_database(config: ConnectionConfigSnapshot):
    connection_type = config.connection_type.strip().lower()
    if connection_type not in {"pgsql", "postgres", "postgresql"}:
        raise HTTPException(status_code=400, detail=f"暂不支持该任务结果来源数据库类型: {config.connection_type}")
    return psycopg2.connect(
        host=config.connection_url,
        port=config.port,
        dbname=config.database_name,
        user=config.db_login_name,
        password=config.db_login_password,
        connect_timeout=10,
    )


# 函数：load_task_config_snapshot
def load_task_config_snapshot(task_config_id: int) -> TaskConfigSnapshot:
    settings = load_database_settings()
    try:
        with connect_database(settings) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select id, task_name, project_id, cli_id, database_config_id, selected_tables
                    from tb_task_config
                    where id = %s
                    """,
                    (task_config_id,),
                )
                row = cursor.fetchone()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"读取任务配置失败: {exc}") from exc

    if not row:
        raise HTTPException(status_code=404, detail=f"任务配置不存在: {task_config_id}")
    if row[4] is None:
        raise HTTPException(status_code=400, detail="任务配置未关联数据库")
    return TaskConfigSnapshot(
        id=int(row[0]),
        task_name=str(row[1] or ""),
        project_id=int(row[2]),
        cli_id=str(row[3] or ""),
        database_config_id=int(row[4]),
        selected_tables=str(row[5] or ""),
    )


# 函数：load_connection_config_snapshot
def load_connection_config_snapshot(connection_config_id: int) -> ConnectionConfigSnapshot:
    settings = load_database_settings()
    try:
        with connect_database(settings) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select id, connection_type, connection_url, database_name, port, db_login_name, db_login_password
                    from tb_connection
                    where id = %s
                    """,
                    (connection_config_id,),
                )
                row = cursor.fetchone()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"读取数据库配置失败: {exc}") from exc

    if not row:
        raise HTTPException(status_code=404, detail=f"数据库配置不存在: {connection_config_id}")
    return ConnectionConfigSnapshot(
        id=int(row[0]),
        connection_type=str(row[1] or ""),
        connection_url=str(row[2] or ""),
        database_name=str(row[3] or ""),
        port=int(row[4] or 5432),
        db_login_name=str(row[5] or ""),
        db_login_password=str(row[6] or ""),
    )


# 函数：load_task_result_snapshot
def load_task_result_snapshot(task_result_id: int) -> TaskResultSnapshot:
    settings = load_database_settings()
    try:
        with connect_database(settings) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select id, result_name, task_config_id, project_id, cli_id,
                           database_config_id, status, result_content
                    from tb_task_result
                    where id = %s
                    """,
                    (task_result_id,),
                )
                row = cursor.fetchone()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"读取任务结果失败: {exc}") from exc

    if not row:
        raise HTTPException(status_code=404, detail=f"任务结果不存在: {task_result_id}")
    if row[5] is None:
        raise HTTPException(status_code=400, detail="任务结果未关联源数据库")
    return TaskResultSnapshot(
        id=int(row[0]),
        result_name=str(row[1] or ""),
        task_config_id=int(row[2]) if row[2] is not None else None,
        project_id=int(row[3]),
        cli_id=str(row[4] or ""),
        database_config_id=int(row[5]),
        status=str(row[6] or ""),
        result_content=str(row[7] or ""),
    )


# 函数：load_task_result_snapshots
def load_task_result_snapshots(task_result_ids: list[int]) -> list[TaskResultSnapshot]:
    if not task_result_ids:
        return []
    settings = load_database_settings()
    try:
        with connect_database(settings) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select id, result_name, task_config_id, project_id, cli_id,
                           database_config_id, status, result_content
                    from tb_task_result
                    where id = any(%s)
                    """,
                    (task_result_ids,),
                )
                rows = cursor.fetchall()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"读取任务结果失败: {exc}") from exc

    snapshots_by_id = {
        int(row[0]): TaskResultSnapshot(
            id=int(row[0]),
            result_name=str(row[1] or ""),
            task_config_id=int(row[2]) if row[2] is not None else None,
            project_id=int(row[3]),
            cli_id=str(row[4] or ""),
            database_config_id=int(row[5]) if row[5] is not None else 0,
            status=str(row[6] or ""),
            result_content=str(row[7] or ""),
        )
        for row in rows
    }
    missing_ids = [item for item in task_result_ids if item not in snapshots_by_id]
    if missing_ids:
        raise HTTPException(status_code=404, detail=f"任务结果不存在: {missing_ids}")
    invalid_ids = [item.id for item in snapshots_by_id.values() if item.database_config_id <= 0]
    if invalid_ids:
        raise HTTPException(status_code=400, detail=f"任务结果未关联源数据库: {invalid_ids}")
    return [snapshots_by_id[item] for item in task_result_ids]


# 函数：load_task_run_snapshot
def load_task_run_snapshot(task_run_id: int) -> TaskRunSnapshot:
    settings = load_database_settings()
    try:
        with connect_database(settings) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select id, task_name, cli_id, ai_prompt_json, ai_response_json
                    from tb_task_run
                    where id = %s
                    """,
                    (task_run_id,),
                )
                row = cursor.fetchone()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"读取任务批次失败: {exc}") from exc

    if not row:
        raise HTTPException(status_code=404, detail=f"任务批次不存在: {task_run_id}")
    return TaskRunSnapshot(
        id=int(row[0]),
        task_name=str(row[1] or ""),
        cli_id=str(row[2] or ""),
        ai_prompt_json=str(row[3] or ""),
        ai_response_json=str(row[4] or ""),
    )


# 函数：load_task_run_result_ids
def load_task_run_result_ids(task_run_id: int) -> list[int]:
    settings = load_database_settings()
    try:
        with connect_database(settings) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select task_result_id
                    from tb_task_run_result
                    where task_run_id = %s
                    order by id asc
                    """,
                    (task_run_id,),
                )
                rows = cursor.fetchall()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"读取批次任务结果失败: {exc}") from exc
    return [int(row[0]) for row in rows]


# 函数：limit_text
def limit_text(value: Any, max_length: int) -> str:
    text = str(value or "")
    return text if len(text) <= max_length else text[:max_length]


# 函数：update_task_result_state
def update_task_result_state(
    task_result_id: int,
    status: str,
    summary: str,
    result_content: dict[str, Any] | None,
    error_message: str = "",
) -> None:
    settings = load_database_settings()
    content_json = json.dumps(result_content, ensure_ascii=False) if result_content is not None else None
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                update tb_task_result
                set status = %s,
                    summary = %s,
                    result_content = coalesce(%s, result_content),
                    error_message = %s,
                    completed_at = case when %s in ('SUCCESS', 'FAILED') then now() else completed_at end,
                    updated_at = now()
                where id = %s
                """,
                (status, summary, content_json, error_message, status, task_result_id),
            )
        connection.commit()


# 函数：batch_update_task_result_states
def batch_update_task_result_states(rows: list[tuple[int, str, str, dict[str, Any] | None, str]]) -> None:
    if not rows:
        return
    values = [
        (
            item[0],
            item[1],
            limit_text(item[2], 2000),
            json.dumps(item[3], ensure_ascii=False) if item[3] is not None else None,
            limit_text(item[4], 4000),
        )
        for item in rows
    ]
    settings = load_database_settings()
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            execute_values(
                cursor,
                """
                update tb_task_result as target
                set status = data.status,
                    summary = data.summary,
                    result_content = coalesce(data.result_content, target.result_content),
                    error_message = data.error_message,
                    completed_at = case when data.status in ('SUCCESS', 'FAILED') then now() else target.completed_at end,
                    updated_at = now()
                from (values %s) as data(id, status, summary, result_content, error_message)
                where target.id = data.id
                """,
                values,
                template="(%s::bigint, %s::varchar, %s::varchar, %s::text, %s::varchar)",
                page_size=1000,
            )
        connection.commit()


# 函数：update_task_run_ai_response
def update_task_run_ai_response(task_run_id: int, ai_response_json: dict[str, Any]) -> str:
    content_json = json.dumps(ai_response_json, ensure_ascii=False)
    settings = load_database_settings()
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                update tb_task_run
                set ai_response_json = %s,
                    updated_at = now()
                where id = %s
                """,
                (content_json, task_run_id),
            )
        connection.commit()
    return content_json


# 函数：parse_selected_tables
def parse_selected_tables(value: str | None) -> list[str]:
    if not value:
        return []
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return []
    if not isinstance(parsed, list):
        return []
    return [str(item).strip() for item in parsed if str(item).strip()]


# 函数：normalize_table_name
def normalize_table_name(value: str) -> str:
    cleaned = value.strip().lower()
    return cleaned if "." in cleaned else f"public.{cleaned}"


# 函数：ensure_word_clean_sentence_task
def ensure_word_clean_sentence_task(tables: list[str]) -> None:
    normalized = {normalize_table_name(table) for table in tables}
    if "public.word_clean_sentence" not in normalized:
        raise HTTPException(status_code=400, detail="第一版仅支持从 public.word_clean_sentence 生成单词评分任务结果")


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
            "model": str(item.get("model", "")),
            "reasoningEffort": str(item.get("reasoningEffort", "")),
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


# 函数：generated_script_relative_path
def generated_script_relative_path(task_config_id: int) -> str:
    return f".runtime/generated-scripts/task-config-{task_config_id}/score_word_clean_sentence.py"


# 函数：write_word_clean_sentence_score_script
def write_word_clean_sentence_score_script(task_config: TaskConfigSnapshot, connection_config: ConnectionConfigSnapshot) -> str:
    relative_path = generated_script_relative_path(task_config.id)
    script_path = PROJECT_ROOT / relative_path
    script_path.parent.mkdir(parents=True, exist_ok=True)
    script_content = f'''#!/usr/bin/env python3
"""Generated score script for AI Task Center task config {task_config.id}.

This script is a stable execution entry for future CLI/model scoring.
It reads one task-result JSON payload, loads candidate sentences from
public.word_clean_sentence, and leaves the scoring write-back hook in one place.
"""

import json
from pathlib import Path

import psycopg2


SOURCE_DB = {{
    "host": "{connection_config.connection_url}",
    "port": {connection_config.port},
    "dbname": "{connection_config.database_name}",
    "user": "{connection_config.db_login_name}",
    "password": "{connection_config.db_login_password}",
}}


# Function: load_payload
def load_payload(path: str) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


# Function: load_candidate_sentences
def load_candidate_sentences(payload: dict) -> list[dict]:
    write_back = payload.get("writeBack") or {{}}
    candidate_map = write_back.get("candidateMap") or {{}}
    ids = [
        candidate.get("candidateSentenceId")
        for candidate in candidate_map.values()
        if isinstance(candidate, dict) and candidate.get("candidateSentenceId")
    ]
    if not ids:
        return []
    with psycopg2.connect(**SOURCE_DB) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                select id, word_clean_id, word, model_name, sentence, sentence_translation
                from public.word_clean_sentence
                where id = any(%s)
                order by id
                """,
                (ids,),
            )
            rows = cursor.fetchall()
    return [
        {{
            "id": row[0],
            "wordCleanId": row[1],
            "word": row[2],
            "modelName": row[3],
            "sentence": row[4],
            "sentenceTranslation": row[5],
        }}
        for row in rows
    ]


# Function: main
def main(payload_path: str) -> None:
    payload = load_payload(payload_path)
    candidates = load_candidate_sentences(payload)
    print(json.dumps({{
        "aiPrompt": payload.get("aiPrompt", ""),
        "writeBack": payload.get("writeBack", {{}}),
        "candidateCount": len(candidates),
    }}, ensure_ascii=False))


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("payload_path")
    args = parser.parse_args()
    main(args.payload_path)
'''
    script_path.write_text(script_content, encoding="utf-8")
    script_path.chmod(0o755)
    return relative_path


# 函数：load_existing_word_clean_ids
def load_existing_word_clean_ids(task_config_id: int, source_description: str) -> set[int]:
    existing: set[int] = set()
    settings = load_database_settings()
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                select result_content
                from tb_task_result
                where task_config_id = %s and source_description = %s
                """,
                (task_config_id, source_description),
            )
            for (content,) in cursor.fetchall():
                try:
                    payload = json.loads(content or "{}")
                except json.JSONDecodeError:
                    continue
                word_clean_id = payload.get("wordCleanId")
                if word_clean_id is None and isinstance(payload.get("writeBack"), dict):
                    word_clean_id = payload["writeBack"].get("wordCleanId")
                if isinstance(word_clean_id, int):
                    existing.add(word_clean_id)
    return existing


# 函数：delete_existing_generated_results
def delete_existing_generated_results(task_config_id: int, source_description: str) -> int:
    settings = load_database_settings()
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                delete from tb_task_result
                where task_config_id = %s and source_description = %s
                """,
                (task_config_id, source_description),
            )
            deleted = cursor.rowcount
        connection.commit()
    return int(deleted or 0)


# 函数：fetch_word_clean_sentence_groups
def fetch_word_clean_sentence_groups(connection_config: ConnectionConfigSnapshot) -> list[dict[str, Any]]:
    try:
        with connect_source_database(connection_config) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select word_clean_id,
                           word,
                           id,
                           model_name,
                           sentence,
                           sentence_translation,
                           score is not null as has_score
                    from public.word_clean_sentence
                    where word_clean_id is not null
                    order by word_clean_id, id
                    """
                )
                rows = cursor.fetchall()
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"读取 word_clean_sentence 失败: {exc}") from exc

    groups_by_id: dict[int, dict[str, Any]] = {}
    for row in rows:
        word_clean_id = int(row[0])
        group = groups_by_id.setdefault(
            word_clean_id,
            {
                "wordCleanId": word_clean_id,
                "word": str(row[1] or ""),
                "candidates": [],
            },
        )
        if not group["word"] and row[1]:
            group["word"] = str(row[1])
        group["candidates"].append(
            {
                "id": int(row[2]),
                "modelName": str(row[3] or ""),
                "sentence": str(row[4] or ""),
                "sentenceTranslation": str(row[5] or ""),
                "hasScore": bool(row[6]),
            }
        )

    groups = list(groups_by_id.values())
    for group in groups:
        candidates = group["candidates"]
        group["candidateCount"] = len(candidates)
        group["scoredCount"] = sum(1 for candidate in candidates if candidate.get("hasScore"))
    return groups


# 函数：candidate_label
def candidate_label(index: int) -> str:
    label = ""
    current = index
    while current >= 0:
        label = chr(ord("A") + current % 26) + label
        current = current // 26 - 1
    return label


# 函数：prompt_safe_text
def prompt_safe_text(value: Any) -> str:
    return str(value or "").translate(PROMPT_DIGIT_TRANSLATION)


# 函数：build_ai_score_prompt
def build_ai_score_prompt(word: str, candidates: list[dict[str, Any]]) -> str:
    lines = [
        "你是英语例句质量评审助手。",
        f"目标单词：{prompt_safe_text(word)}",
        "请给每条候选例句评分，并选出最适合学习者记忆目标单词的一条。",
        f"评分必须是 {SCORE_MIN}-{SCORE_MAX} 之间的整数，分数越高代表越适合。",
        "同一个目标单词下，所有候选例句的分数必须互不相同。",
        "不要返回数据库 ID 或额外说明。",
        "候选例句：",
    ]
    for candidate in candidates:
        label = candidate["label"]
        lines.extend(
            [
                f"{label}. 来源：{prompt_safe_text(candidate.get('modelName')) or '未知'}",
                f"{label}. 英文：{prompt_safe_text(candidate.get('sentence'))}",
                f"{label}. 中文：{prompt_safe_text(candidate.get('sentenceTranslation'))}",
            ]
        )
    lines.extend(
        [
            "请只返回 JSON，字段如下：",
            '{"scores":[{"candidate":"A","score":95,"reason":"简短原因"}],"bestCandidate":"A"}',
            "scores 必须覆盖全部候选；bestCandidate 必须等于最高分候选。",
        ]
    )
    return "\n".join(lines)


# 函数：build_write_back_payload
def build_write_back_payload(word_clean_id: int, word: str, candidates: list[dict[str, Any]]) -> dict[str, Any]:
    candidate_map = {
        candidate["label"]: {
            "candidateSentenceId": candidate["id"],
            "wordCleanId": word_clean_id,
            "word": word,
            "modelName": candidate.get("modelName", ""),
            "sentence": candidate.get("sentence", ""),
            "sentenceTranslation": candidate.get("sentenceTranslation", ""),
        }
        for candidate in candidates
    }
    return {
        "sourceTable": "public.word_clean_sentence",
        "bestSentenceTable": "public.word_clean_best_sentence",
        "wordCleanId": word_clean_id,
        "word": word,
        "candidateMap": candidate_map,
        "scoreRange": {"min": SCORE_MIN, "max": SCORE_MAX},
        "uniqueScoreRequired": True,
        "aiResultContract": {
            "scores": [
                {
                    "candidate": "候选字母",
                    "score": "1-100 的整数，同一 wordCleanId 下不可重复",
                    "reason": "AI 返回的评分原因",
                }
            ],
            "bestCandidate": "最高分候选字母",
        },
        "writeBackPlan": [
            {
                "step": "update_all_source_candidate_scores",
                "table": "public.word_clean_sentence",
                "foreach": "aiResult.scores",
                "match": {"id": "candidateMap[score.candidate].candidateSentenceId"},
                "set": {
                    "score": "score.score",
                    "score_reason": "score.reason",
                    "score_model_name": "executedCliLabel",
                    "scored_at": "now()",
                },
            },
            {
                "step": "select_highest_score_candidate",
                "rule": "按 score 降序取第一条；如果出现相同分数则视为无效结果，不进入 TTS",
                "output": "bestScoreItem",
            },
            {
                "step": "upsert_best_sentence",
                "table": "public.word_clean_best_sentence",
                "match": {"word_clean_id": "wordCleanId"},
                "set": {
                    "source_sentence_id": "candidateMap[bestScoreItem.candidate].candidateSentenceId",
                    "source_model_name": "candidateMap[bestScoreItem.candidate].modelName",
                    "word": "word",
                    "sentence": "candidateMap[bestScoreItem.candidate].sentence",
                    "sentence_translation": "candidateMap[bestScoreItem.candidate].sentenceTranslation",
                    "score": "bestScoreItem.score",
                    "score_reason": "bestScoreItem.reason",
                    "score_model_name": "executedCliLabel",
                    "scored_at": "now()",
                    "tts_status": "pending",
                },
            },
            {
                "step": "tts_generation_source",
                "table": "public.word_clean_best_sentence",
                "rule": "TTS 任务只读取每个单词最高分句子，优先处理 tts_status = pending 的记录",
            },
        ],
    }


# 函数：build_score_result_rows
def build_score_result_rows(
    task_config: TaskConfigSnapshot,
    tables: list[str],
    script_path: str,
    groups: list[dict[str, Any]],
    existing_word_clean_ids: set[int],
    onboarding_generation_id: str | None = None,
) -> list[tuple[Any, ...]]:
    now = datetime.now(timezone.utc)
    source_tables_json = json.dumps(tables, ensure_ascii=False)
    rows: list[tuple[Any, ...]] = []
    for group in groups:
        word_clean_id = group["wordCleanId"]
        if word_clean_id in existing_word_clean_ids:
            continue
        word = str(group["word"]).strip()
        candidates = [
            {
                **candidate,
                "label": candidate_label(index),
            }
            for index, candidate in enumerate(group["candidates"])
        ]
        display_word = word[:80] if word else str(word_clean_id)
        ai_prompt = build_ai_score_prompt(word, candidates)
        write_back = build_write_back_payload(word_clean_id, word, candidates)
        payload = merge_onboarding_generation_metadata(
            {
                "taskType": "word_clean_sentence_score",
                "word": word,
                "sourceTable": "public.word_clean_sentence",
                "aiPrompt": ai_prompt,
                "writeBack": write_back,
                "scriptPath": script_path,
            },
            onboarding_generation_id,
        )
        rows.append(
            (
                f"{task_config.task_name} - {display_word} ({word_clean_id})",
                None,
                task_config.id,
                task_config.project_id,
                task_config.cli_id,
                task_config.database_config_id,
                source_tables_json,
                SCORE_SOURCE_DESCRIPTION,
                "PENDING",
                f"{group['candidateCount']} 条候选句待评分",
                json.dumps(payload, ensure_ascii=False),
                "",
                now,
                None,
                now,
                now,
            )
        )
    return rows


# 函数：insert_task_result_rows
def insert_task_result_rows(rows: list[tuple[Any, ...]]) -> int:
    if not rows:
        return 0
    settings = load_database_settings()
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            execute_values(
                cursor,
                """
                insert into tb_task_result (
                    result_name,
                    task_run_id,
                    task_config_id,
                    project_id,
                    cli_id,
                    database_config_id,
                    source_tables,
                    source_description,
                    status,
                    summary,
                    result_content,
                    error_message,
                    parsed_at,
                    completed_at,
                    created_at,
                    updated_at
                ) values %s
                """,
                rows,
                page_size=1000,
            )
        connection.commit()
    return len(rows)


# 函数：load_formal_result_contents
def load_formal_result_contents(task_config_id: int) -> list[str | None]:
    settings = load_database_settings()
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                select result_content
                from tb_task_result
                where task_config_id = %s
                  and source_description = %s
                order by id
                """,
                (task_config_id, SCORE_SOURCE_DESCRIPTION),
            )
            return [row[0] for row in cursor.fetchall()]


# 函数：generate_word_clean_sentence_results
def generate_word_clean_sentence_results(
    task_config_id: int,
    overwrite: bool,
    onboarding_generation_id: str | None = None,
) -> dict[str, Any]:
    generation_id = normalize_onboarding_generation_id(onboarding_generation_id)
    if generation_id is not None:
        recovered = build_recovered_generation_response(
            task_config_id,
            generation_id,
            load_formal_result_contents(task_config_id),
        )
        if recovered is not None:
            return {
                **recovered,
                "mode": "word_clean_sentence_score",
                "sourceTable": "public.word_clean_sentence",
            }
    task_config = load_task_config_snapshot(task_config_id)
    tables = parse_selected_tables(task_config.selected_tables)
    ensure_word_clean_sentence_task(tables)
    connection_config = load_connection_config_snapshot(task_config.database_config_id)
    script_path = write_word_clean_sentence_score_script(task_config, connection_config)
    deleted_count = delete_existing_generated_results(task_config.id, SCORE_SOURCE_DESCRIPTION) if overwrite else 0
    existing_ids = set() if overwrite else load_existing_word_clean_ids(task_config.id, SCORE_SOURCE_DESCRIPTION)
    groups = fetch_word_clean_sentence_groups(connection_config)
    rows = build_score_result_rows(
        task_config,
        tables,
        script_path,
        groups,
        existing_ids,
        generation_id,
    )
    inserted_count = insert_task_result_rows(rows)
    return {
        "accepted": True,
        "mode": "word_clean_sentence_score",
        "taskConfigId": task_config.id,
        "sourceTable": "public.word_clean_sentence",
        "scriptPath": script_path,
        "totalGroups": len(groups),
        "insertedCount": inserted_count,
        "skippedCount": len(groups) - inserted_count,
        "deletedCount": deleted_count,
        "overwrite": overwrite,
    }


# 函数：parse_task_result_payload
def parse_task_result_payload(task_result: TaskResultSnapshot) -> dict[str, Any]:
    try:
        payload = json.loads(task_result.result_content or "{}")
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=400, detail=f"任务结果 JSON 解析失败: {exc}") from exc
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="任务结果正文必须是 JSON 对象")
    if payload.get("taskType") != "word_clean_sentence_score":
        raise HTTPException(status_code=400, detail="第一版仅支持 word_clean_sentence_score 任务结果")
    if not isinstance(payload.get("aiPrompt"), str) or not payload["aiPrompt"].strip():
        raise HTTPException(status_code=400, detail="任务结果缺少 AI 评分提示词")
    if not isinstance(payload.get("writeBack"), dict):
        raise HTTPException(status_code=400, detail="任务结果缺少回填源数据库详情")
    return payload


# 函数：terminate_process_group
def terminate_process_group(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=3)
    except ProcessLookupError:
        return


# 函数：run_cli_prompt
def run_cli_prompt(cli_config: dict[str, Any], prompt: str) -> dict[str, Any]:
    command = str(cli_config.get("command") or "").strip()
    if not command:
        raise ValueError("CLI 命令为空")
    default_args = [str(item) for item in cli_config.get("defaultArgs", []) if str(item).strip()]
    model = str(cli_config.get("model") or "").strip()
    reasoning_effort = str(cli_config.get("reasoningEffort") or "").strip()
    effective_args = list(default_args)
    if is_codex_cli_config(cli_config):
        if model:
            effective_args.extend(["--model", model])
        if reasoning_effort:
            effective_args.extend(["-c", f'model_reasoning_effort="{reasoning_effort}"'])
        if "--ephemeral" not in effective_args:
            effective_args.append("--ephemeral")
    timeout = int(cli_config.get("timeoutSeconds") or 300)
    working_directory = str(cli_config.get("workingDirectory") or PROJECT_ROOT)
    # Worker 可能由 Codex Desktop/launchd 启动，继承 CODEX_SANDBOX 等内部变量后，
    # 子 Codex 会被当作嵌套沙箱进程直接 SIGKILL。后台 CLI 只保留普通用户环境。
    child_environment = os.environ.copy()
    for environment_name in list(child_environment):
        if environment_name.startswith("CODEX_"):
            child_environment.pop(environment_name, None)
    child_environment["CODEX_CI"] = "1"
    child_environment.setdefault("TERM", "dumb")
    codex_cli = is_codex_cli_config(cli_config)
    if codex_cli:
        process_args = [command, *effective_args, "-"]
        prompt_input: str | None = prompt
    else:
        shell_command = shlex.join([command, *effective_args, prompt])
        process_args = ["zsh", "-ic", shell_command]
        prompt_input = None
    try:
        process = subprocess.Popen(
            process_args,
            cwd=working_directory or None,
            env=child_environment,
            stdin=subprocess.PIPE if prompt_input is not None else subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            start_new_session=True,
        )
        stdout_text, stderr_text = process.communicate(input=prompt_input, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        terminate_process_group(process)
        raise ValueError(f"CLI 执行超时: {timeout} 秒") from exc
    except Exception as exc:
        raise ValueError(f"CLI 执行失败: {exc}") from exc

    stdout = stdout_text.strip()
    stderr = stderr_text.strip()
    if process.returncode != 0:
        if process.returncode < 0:
            message = f"进程被信号 {-process.returncode} 终止"
        else:
            message = stderr or stdout or f"退出码 {process.returncode}"
        raise ValueError(f"CLI 返回失败: {message}")
    if not stdout:
        raise ValueError("CLI 没有返回内容")
    return {
        "returnCode": process.returncode,
        "stdout": stdout,
        "stderr": stderr,
        "command": command,
        "defaultArgs": default_args,
        "effectiveArgs": effective_args,
        "model": model,
        "reasoningEffort": reasoning_effort,
        "workingDirectory": working_directory,
    }


# 函数：is_codex_cli_config
def is_codex_cli_config(cli_config: dict[str, Any]) -> bool:
    config_id = str(cli_config.get("id") or "").strip().lower()
    command = str(cli_config.get("command") or "").strip().lower()
    return config_id == "codex" or "codex" in Path(command).name


# 函数：extract_json_payload
def extract_json_payload(output: str) -> dict[str, Any]:
    text = output.strip()
    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass

    for start in [index for index, char in enumerate(text) if char == "{"]:
        depth = 0
        in_string = False
        escaped = False
        for index in range(start, len(text)):
            char = text[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue
            if char == '"':
                in_string = True
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    candidate = text[start:index + 1]
                    try:
                        parsed = json.loads(candidate)
                    except json.JSONDecodeError:
                        break
                    if isinstance(parsed, dict):
                        return parsed
    raise ValueError("CLI 返回内容中没有可解析的 JSON 对象")


# 函数：normalize_score_value
def normalize_score_value(value: Any) -> int:
    if isinstance(value, bool):
        raise ValueError("score 不能是布尔值")
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    raise ValueError("score 必须是 1-100 的整数")


# 函数：validate_ai_score_result
def validate_ai_score_result(ai_result: dict[str, Any], write_back: dict[str, Any]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    candidate_map = write_back.get("candidateMap")
    if not isinstance(candidate_map, dict) or not candidate_map:
        raise ValueError("回填详情缺少 candidateMap")
    expected_labels = {str(label) for label in candidate_map.keys()}
    scores = ai_result.get("scores")
    if not isinstance(scores, list) or not scores:
        raise ValueError("AI 返回结果必须包含 scores 数组")

    normalized: list[dict[str, Any]] = []
    seen_labels: set[str] = set()
    seen_scores: set[int] = set()
    for item in scores:
        if not isinstance(item, dict):
            raise ValueError("scores 每一项必须是对象")
        label = str(item.get("candidate") or "").strip()
        if label not in expected_labels:
            raise ValueError(f"未知候选标识: {label}")
        if label in seen_labels:
            raise ValueError(f"候选 {label} 重复评分")
        score = normalize_score_value(item.get("score"))
        if score < SCORE_MIN or score > SCORE_MAX:
            raise ValueError(f"候选 {label} 分数超出 {SCORE_MIN}-{SCORE_MAX}")
        if score in seen_scores:
            raise ValueError(f"分数 {score} 重复，同一个单词下候选句分数不允许相同")
        reason = str(item.get("reason") or "").strip()
        if not reason:
            raise ValueError(f"候选 {label} 缺少评分原因")
        seen_labels.add(label)
        seen_scores.add(score)
        normalized.append({"candidate": label, "score": score, "reason": reason})

    if seen_labels != expected_labels:
        missing = ", ".join(sorted(expected_labels - seen_labels))
        raise ValueError(f"AI 返回结果未覆盖全部候选: {missing}")

    best_score_item = max(normalized, key=lambda item: item["score"])
    best_candidate = str(ai_result.get("bestCandidate") or "").strip()
    if best_candidate != best_score_item["candidate"]:
        raise ValueError("bestCandidate 必须等于最高分候选")
    return normalized, best_score_item


# 函数：backfill_word_clean_sentence_score
def backfill_word_clean_sentence_score(
    connection_config: ConnectionConfigSnapshot,
    write_back: dict[str, Any],
    scores: list[dict[str, Any]],
    best_score_item: dict[str, Any],
    executed_cli_label: str,
) -> dict[str, Any]:
    candidate_map = write_back["candidateMap"]
    word_clean_id = int(write_back["wordCleanId"])
    word = str(write_back.get("word") or "")
    score_model_name = executed_cli_label[:128]
    best_candidate = candidate_map[best_score_item["candidate"]]
    updated_sentence_ids: list[int] = []

    try:
        with connect_source_database(connection_config) as connection:
            with connection.cursor() as cursor:
                for score_item in scores:
                    candidate = candidate_map[score_item["candidate"]]
                    sentence_id = int(candidate["candidateSentenceId"])
                    cursor.execute(
                        """
                        update public.word_clean_sentence
                        set score = %s,
                            score_reason = %s,
                            score_model_name = %s,
                            scored_at = now()
                        where id = %s and word_clean_id = %s
                        """,
                        (score_item["score"], score_item["reason"], score_model_name, sentence_id, word_clean_id),
                    )
                    if cursor.rowcount != 1:
                        raise ValueError(f"源句子不存在或未更新: {sentence_id}")
                    updated_sentence_ids.append(sentence_id)

                cursor.execute(
                    """
                    insert into public.word_clean_best_sentence (
                        word_clean_id,
                        word,
                        source_sentence_id,
                        source_model_name,
                        sentence,
                        sentence_translation,
                        score,
                        score_reason,
                        score_model_name,
                        scored_at,
                        tts_status,
                        tts_error_message,
                        updated_at
                    ) values (
                        %s, %s, %s, %s, %s, %s, %s, %s, %s, now(), 'pending', '', now()
                    )
                    on conflict (word_clean_id) do update
                    set source_sentence_id = excluded.source_sentence_id,
                        source_model_name = excluded.source_model_name,
                        word = excluded.word,
                        sentence = excluded.sentence,
                        sentence_translation = excluded.sentence_translation,
                        score = excluded.score,
                        score_reason = excluded.score_reason,
                        score_model_name = excluded.score_model_name,
                        scored_at = excluded.scored_at,
                        tts_status = 'pending',
                        tts_error_message = '',
                        updated_at = now()
                    returning id
                    """,
                    (
                        word_clean_id,
                        word,
                        int(best_candidate["candidateSentenceId"]),
                        str(best_candidate.get("modelName") or ""),
                        str(best_candidate.get("sentence") or ""),
                        str(best_candidate.get("sentenceTranslation") or ""),
                        best_score_item["score"],
                        best_score_item["reason"],
                        score_model_name,
                    ),
                )
                best_sentence_id = int(cursor.fetchone()[0])
            connection.commit()
    except Exception:
        raise

    return {
        "updatedSentenceIds": updated_sentence_ids,
        "bestSentenceId": best_sentence_id,
        "bestCandidate": best_score_item["candidate"],
        "bestScore": best_score_item["score"],
        "ttsStatus": "pending",
    }


# 函数：build_prepared_score_result
def build_prepared_score_result(
    task_result: TaskResultSnapshot,
    payload: dict[str, Any],
    cli_response: dict[str, Any],
    scores: list[dict[str, Any]],
    best_score_item: dict[str, Any],
    cli_config: dict[str, Any],
    effective_cli_id: str,
) -> dict[str, Any]:
    write_back = payload["writeBack"]
    candidate_map = write_back["candidateMap"]
    word_clean_id = int(write_back["wordCleanId"])
    word = str(write_back.get("word") or "")
    cli_label = str(cli_config.get("label") or effective_cli_id)
    score_model_name = str(cli_config.get("model") or cli_label)[:128]
    best_candidate = candidate_map[best_score_item["candidate"]]
    source_updates = []
    for score_item in scores:
        candidate = candidate_map[score_item["candidate"]]
        source_updates.append(
            (
                int(candidate["candidateSentenceId"]),
                word_clean_id,
                int(score_item["score"]),
                str(score_item["reason"]),
                score_model_name,
            )
        )
    best_upsert = (
        word_clean_id,
        word,
        int(best_candidate["candidateSentenceId"]),
        str(best_candidate.get("modelName") or ""),
        str(best_candidate.get("sentence") or ""),
        str(best_candidate.get("sentenceTranslation") or ""),
        int(best_score_item["score"]),
        str(best_score_item["reason"]),
        score_model_name,
    )
    summary = (
        f"已评分 {len(scores)} 条候选句，最高分 "
        f"{best_score_item['candidate']}={best_score_item['score']}，TTS 待生成"
    )
    return {
        "taskResult": task_result,
        "payload": payload,
        "scores": scores,
        "bestScoreItem": best_score_item,
        "cliResponse": cli_response,
        "effectiveCliId": effective_cli_id,
        "cliConfig": cli_config,
        "sourceUpdates": source_updates,
        "bestUpsert": best_upsert,
        "summary": summary,
        "backfillResult": {
            "updatedSentenceIds": [item[0] for item in source_updates],
            "bestCandidate": best_score_item["candidate"],
            "bestScore": best_score_item["score"],
            "ttsStatus": "pending",
        },
    }


# 函数：build_completed_payload
def build_completed_payload(prepared: dict[str, Any]) -> dict[str, Any]:
    payload = prepared["payload"]
    cli_response = prepared["cliResponse"]
    cli_config = prepared["cliConfig"]
    best_score_item = prepared["bestScoreItem"]
    return {
        **payload,
        "aiRawOutput": cli_response["stdout"],
        "aiResult": {
            "scores": prepared["scores"],
            "bestCandidate": best_score_item["candidate"],
        },
        "execution": {
            "cliId": prepared["effectiveCliId"],
            "cliLabel": cli_config.get("label"),
            "command": cli_response["command"],
            "defaultArgs": cli_response["defaultArgs"],
            "effectiveArgs": cli_response["effectiveArgs"],
            "model": cli_response["model"],
            "reasoningEffort": cli_response["reasoningEffort"],
            "workingDirectory": cli_response["workingDirectory"],
            "returnCode": cli_response["returnCode"],
            "stderr": cli_response["stderr"],
            "processedAt": datetime.now(timezone.utc).isoformat(),
            "mode": "batch",
        },
        "backfillResult": prepared["backfillResult"],
    }


# 函数：build_completed_payload_from_task_run_batch
def build_completed_payload_from_task_run_batch(prepared: dict[str, Any], task_run: TaskRunSnapshot) -> dict[str, Any]:
    payload = prepared["payload"]
    cli_response = prepared["cliResponse"]
    cli_config = prepared["cliConfig"]
    best_score_item = prepared["bestScoreItem"]
    return {
        **payload,
        "aiResult": {
            "scores": prepared["scores"],
            "bestCandidate": best_score_item["candidate"],
        },
        "execution": {
            "mode": "task-run-json-batch",
            "taskRunId": task_run.id,
            "itemKey": prepared["itemKey"],
            "cliId": prepared["effectiveCliId"],
            "cliLabel": cli_config.get("label"),
            "command": cli_response["command"],
            "defaultArgs": cli_response["defaultArgs"],
            "effectiveArgs": cli_response["effectiveArgs"],
            "model": cli_response["model"],
            "reasoningEffort": cli_response["reasoningEffort"],
            "workingDirectory": cli_response["workingDirectory"],
            "returnCode": cli_response["returnCode"],
            "stderr": cli_response["stderr"],
            "processedAt": datetime.now(timezone.utc).isoformat(),
            "batchAiResponseRef": "tb_task_run.ai_response_json",
        },
        "backfillResult": prepared["backfillResult"],
    }


# 函数：parse_task_run_batch_prompt
def parse_task_run_batch_prompt(task_run: TaskRunSnapshot) -> dict[str, Any]:
    try:
        prompt = json.loads(task_run.ai_prompt_json or "{}")
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=400, detail=f"批次 AI 提示词 JSON 解析失败: {exc}") from exc
    if not isinstance(prompt, dict):
        raise HTTPException(status_code=400, detail="批次 AI 提示词必须是 JSON 对象")
    if prompt.get("taskType") != "word_clean_sentence_score_batch":
        raise HTTPException(status_code=400, detail="批次 AI 提示词类型不支持")
    items = prompt.get("items")
    if not isinstance(items, list) or not items:
        raise HTTPException(status_code=400, detail="批次 AI 提示词缺少 items")
    item_keys: set[str] = set()
    for item in items:
        if not isinstance(item, dict):
            raise HTTPException(status_code=400, detail="批次 AI 提示词 items 每一项必须是对象")
        item_key = str(item.get("itemKey") or "").strip()
        if not item_key:
            raise HTTPException(status_code=400, detail="批次 AI 提示词 item 缺少 itemKey")
        if item_key in item_keys:
            raise HTTPException(status_code=400, detail=f"批次 AI 提示词 itemKey 重复: {item_key}")
        item_keys.add(item_key)
    return prompt


# 函数：build_task_run_batch_item_contexts
def build_task_run_batch_item_contexts(
    prompt: dict[str, Any],
    task_results: list[TaskResultSnapshot],
) -> dict[str, dict[str, Any]]:
    items = prompt["items"]
    if len(items) != len(task_results):
        raise ValueError(f"批次提示词 items 数量 {len(items)} 与任务结果数量 {len(task_results)} 不一致")
    contexts: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(items):
        task_result = task_results[index]
        payload = parse_task_result_payload(task_result)
        item_key = str(item["itemKey"]).strip()
        contexts[item_key] = {
            "taskResult": task_result,
            "payload": payload,
            "promptItem": item,
        }
    return contexts


# 函数：prepare_items_from_task_run_batch_response
def prepare_items_from_task_run_batch_response(
    ai_result: dict[str, Any],
    contexts: dict[str, dict[str, Any]],
    cli_response: dict[str, Any],
    cli_config: dict[str, Any],
    effective_cli_id: str,
) -> tuple[list[dict[str, Any]], list[tuple[int, str, str, dict[str, Any] | None, str]]]:
    response_items = ai_result.get("items")
    if not isinstance(response_items, list) or not response_items:
        raise ValueError("AI 批次返回结果必须包含 items 数组")

    expected_keys = set(contexts.keys())
    response_by_key: dict[str, dict[str, Any]] = {}
    for item in response_items:
        if not isinstance(item, dict):
            raise ValueError("AI 批次返回 items 每一项必须是对象")
        item_key = str(item.get("itemKey") or "").strip()
        if item_key not in expected_keys:
            raise ValueError(f"AI 批次返回未知 itemKey: {item_key}")
        if item_key in response_by_key:
            raise ValueError(f"AI 批次返回 itemKey 重复: {item_key}")
        response_by_key[item_key] = item

    missing_keys = expected_keys - set(response_by_key.keys())
    if missing_keys:
        raise ValueError(f"AI 批次返回缺少 itemKey: {', '.join(sorted(missing_keys))}")

    prepared_items: list[dict[str, Any]] = []
    failed_rows: list[tuple[int, str, str, dict[str, Any] | None, str]] = []
    for item_key, context in contexts.items():
        task_result = context["taskResult"]
        payload = context["payload"]
        try:
            scores, best_score_item = validate_ai_score_result(response_by_key[item_key], payload["writeBack"])
            prepared = build_prepared_score_result(
                task_result,
                payload,
                cli_response,
                scores,
                best_score_item,
                cli_config,
                effective_cli_id,
            )
            prepared["itemKey"] = item_key
            prepared_items.append(prepared)
        except Exception as exc:
            failed_payload = {
                **payload,
                "aiResult": response_by_key.get(item_key),
                "processorError": str(exc),
                "processedAt": datetime.now(timezone.utc).isoformat(),
                "mode": "task-run-json-batch",
            }
            failed_rows.append((task_result.id, "FAILED", "评分或校验失败", failed_payload, str(exc)))
    return prepared_items, failed_rows


# 函数：fail_task_run_batch_results
def fail_task_run_batch_results(task_results: list[TaskResultSnapshot], message: str, task_run_id: int) -> None:
    failed_rows: list[tuple[int, str, str, dict[str, Any] | None, str]] = []
    for task_result in task_results:
        try:
            payload = parse_task_result_payload(task_result)
            failed_payload = {
                **payload,
                "processorError": message,
                "processedAt": datetime.now(timezone.utc).isoformat(),
                "mode": "task-run-json-batch",
                "taskRunId": task_run_id,
            }
        except Exception:
            failed_payload = None
        failed_rows.append((task_result.id, "FAILED", "批次执行失败", failed_payload, message))
    batch_update_task_result_states(failed_rows)


# 函数：process_word_clean_sentence_task_run_batch
def process_word_clean_sentence_task_run_batch(task_run_id: int, cli_id: str | None = None) -> dict[str, Any]:
    task_run = load_task_run_snapshot(task_run_id)
    prompt = parse_task_run_batch_prompt(task_run)
    task_result_ids = load_task_run_result_ids(task_run.id)
    if not task_result_ids:
        raise HTTPException(status_code=400, detail="任务批次未关联任务结果")

    task_results = load_task_result_snapshots(task_result_ids)
    contexts = build_task_run_batch_item_contexts(prompt, task_results)
    effective_cli_id = (cli_id or task_run.cli_id or "").strip()
    if not effective_cli_id:
        raise HTTPException(status_code=400, detail="任务批次未配置执行 CLI")

    cli_config = find_cli_config(effective_cli_id)
    access = cli_config.get("access", {})
    if not access.get("accessible"):
        raise HTTPException(status_code=400, detail=f"CLI 不可访问: {access.get('message', '')}")

    batch_update_task_result_states([
        (item.id, "RUNNING", "批次 JSON 执行中，正在调用 CLI 评分", None, "")
        for item in task_results
    ])

    try:
        cli_response = run_cli_prompt(cli_config, task_run.ai_prompt_json)
        ai_result = extract_json_payload(cli_response["stdout"])
        ai_response_json = update_task_run_ai_response(task_run.id, {
            "rawOutput": cli_response["stdout"],
            "parsed": ai_result,
            "execution": {
                "mode": "task-run-json-batch",
                "taskRunId": task_run.id,
                "cliId": effective_cli_id,
                "cliLabel": cli_config.get("label"),
                "command": cli_response["command"],
                "defaultArgs": cli_response["defaultArgs"],
                "effectiveArgs": cli_response["effectiveArgs"],
                "model": cli_response["model"],
                "reasoningEffort": cli_response["reasoningEffort"],
                "workingDirectory": cli_response["workingDirectory"],
                "returnCode": cli_response["returnCode"],
                "stderr": cli_response["stderr"],
                "processedAt": datetime.now(timezone.utc).isoformat(),
            },
        })
        prepared_items, failed_rows = prepare_items_from_task_run_batch_response(
            ai_result,
            contexts,
            cli_response,
            cli_config,
            effective_cli_id,
        )

        grouped: dict[int, list[dict[str, Any]]] = {}
        for item in prepared_items:
            task_result = item["taskResult"]
            grouped.setdefault(task_result.database_config_id, []).append(item)

        success_rows: list[tuple[int, str, str, dict[str, Any] | None, str]] = []
        for database_config_id, items in grouped.items():
            try:
                connection_config = load_connection_config_snapshot(database_config_id)
                batch_backfill_word_clean_sentence_scores(connection_config, items)
                for item in items:
                    success_rows.append(
                        (
                            item["taskResult"].id,
                            "SUCCESS",
                            item["summary"],
                            build_completed_payload_from_task_run_batch(item, task_run),
                            "",
                        )
                    )
            except Exception as exc:
                for item in items:
                    failed_payload = {
                        **item["payload"],
                        "aiResult": {
                            "scores": item["scores"],
                            "bestCandidate": item["bestScoreItem"]["candidate"],
                        },
                        "processorError": str(exc),
                        "processedAt": datetime.now(timezone.utc).isoformat(),
                        "mode": "task-run-json-batch",
                    }
                    failed_rows.append((item["taskResult"].id, "FAILED", "批量回填失败", failed_payload, str(exc)))

        batch_update_task_result_states(success_rows + failed_rows)
        return {
            "accepted": True,
            "mode": "task-run-json-batch",
            "taskRunId": task_run.id,
            "taskResultCount": len(task_results),
            "successCount": len(success_rows),
            "failedCount": len(failed_rows),
            "cliId": effective_cli_id,
            "aiResponseJson": ai_response_json,
        }
    except Exception as exc:
        message = str(exc)
        update_task_run_ai_response(task_run.id, {
            "processorError": message,
            "processedAt": datetime.now(timezone.utc).isoformat(),
            "mode": "task-run-json-batch",
        })
        fail_task_run_batch_results(task_results, message, task_run.id)
        if isinstance(exc, HTTPException):
            raise
        raise HTTPException(status_code=500, detail=message) from exc


# 函数：prepare_task_result_for_batch
def prepare_task_result_for_batch(
    task_result: TaskResultSnapshot,
    cli_config: dict[str, Any],
    cli_id: str | None,
) -> dict[str, Any]:
    effective_cli_id = (cli_id or task_result.cli_id or "").strip()
    if not effective_cli_id:
        raise ValueError("任务结果未配置执行 CLI")
    payload = parse_task_result_payload(task_result)
    cli_response = run_cli_prompt(cli_config, payload["aiPrompt"])
    ai_result = extract_json_payload(cli_response["stdout"])
    scores, best_score_item = validate_ai_score_result(ai_result, payload["writeBack"])
    return build_prepared_score_result(
        task_result,
        payload,
        cli_response,
        scores,
        best_score_item,
        cli_config,
        effective_cli_id,
    )


# 函数：batch_backfill_word_clean_sentence_scores
def batch_backfill_word_clean_sentence_scores(
    connection_config: ConnectionConfigSnapshot,
    prepared_items: list[dict[str, Any]],
) -> None:
    source_update_rows = [
        update_row
        for item in prepared_items
        for update_row in item["sourceUpdates"]
    ]
    best_upsert_rows = [item["bestUpsert"] for item in prepared_items]
    if not source_update_rows or not best_upsert_rows:
        return

    with connect_source_database(connection_config) as connection:
        with connection.cursor() as cursor:
            execute_values(
                cursor,
                """
                update public.word_clean_sentence as target
                set score = data.score,
                    score_reason = data.score_reason,
                    score_model_name = data.score_model_name,
                    scored_at = now()
                from (values %s) as data(id, word_clean_id, score, score_reason, score_model_name)
                where target.id = data.id
                  and target.word_clean_id = data.word_clean_id
                """,
                source_update_rows,
                template="(%s::bigint, %s::bigint, %s::integer, %s::text, %s::varchar)",
                page_size=1000,
            )

            execute_values(
                cursor,
                """
                insert into public.word_clean_best_sentence (
                    word_clean_id,
                    word,
                    source_sentence_id,
                    source_model_name,
                    sentence,
                    sentence_translation,
                    score,
                    score_reason,
                    score_model_name,
                    scored_at,
                    tts_status,
                    tts_error_message,
                    updated_at
                ) values %s
                on conflict (word_clean_id) do update
                set source_sentence_id = excluded.source_sentence_id,
                    source_model_name = excluded.source_model_name,
                    word = excluded.word,
                    sentence = excluded.sentence,
                    sentence_translation = excluded.sentence_translation,
                    score = excluded.score,
                    score_reason = excluded.score_reason,
                    score_model_name = excluded.score_model_name,
                    scored_at = excluded.scored_at,
                    tts_status = 'pending',
                    tts_error_message = '',
                    updated_at = now()
                """,
                best_upsert_rows,
                template="(%s::bigint, %s::varchar, %s::bigint, %s::varchar, %s::text, %s::text, %s::integer, %s::text, %s::varchar, now(), 'pending', '', now())",
                page_size=1000,
            )
        connection.commit()


# 函数：process_word_clean_sentence_task_results_batch
def process_word_clean_sentence_task_results_batch(
    task_result_ids: list[int],
    cli_id: str,
    worker_count: int,
) -> dict[str, Any]:
    if not task_result_ids:
        raise HTTPException(status_code=400, detail="任务结果 ID 不能为空")
    if worker_count < 1 or worker_count > 32:
        raise HTTPException(status_code=400, detail="workerCount 需在 1 到 32 之间")

    cli_config = find_cli_config(cli_id)
    access = cli_config.get("access", {})
    if not access.get("accessible"):
        raise HTTPException(status_code=400, detail=f"CLI 不可访问: {access.get('message', '')}")

    task_results = load_task_result_snapshots(task_result_ids)
    batch_update_task_result_states([
        (item.id, "RUNNING", "批量执行中，正在调用 CLI 评分", None, "")
        for item in task_results
    ])

    prepared_items: list[dict[str, Any]] = []
    failed_rows: list[tuple[int, str, str, dict[str, Any] | None, str]] = []
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        future_map = {
            executor.submit(prepare_task_result_for_batch, item, cli_config, cli_id): item
            for item in task_results
        }
        for future in as_completed(future_map):
            task_result = future_map[future]
            try:
                prepared_items.append(future.result())
            except Exception as exc:
                failed_rows.append(
                    (
                        task_result.id,
                        "FAILED",
                        "评分或校验失败",
                        None,
                        str(exc),
                    )
                )

    grouped: dict[int, list[dict[str, Any]]] = {}
    for item in prepared_items:
        task_result = item["taskResult"]
        grouped.setdefault(task_result.database_config_id, []).append(item)

    success_rows: list[tuple[int, str, str, dict[str, Any] | None, str]] = []
    for database_config_id, items in grouped.items():
        try:
            connection_config = load_connection_config_snapshot(database_config_id)
            batch_backfill_word_clean_sentence_scores(connection_config, items)
            for item in items:
                success_rows.append(
                    (
                        item["taskResult"].id,
                        "SUCCESS",
                        item["summary"],
                        build_completed_payload(item),
                        "",
                    )
                )
        except Exception as exc:
            for item in items:
                failed_payload = {
                    **item["payload"],
                    "processorError": str(exc),
                    "processedAt": datetime.now(timezone.utc).isoformat(),
                    "mode": "batch",
                }
                failed_rows.append(
                    (
                        item["taskResult"].id,
                        "FAILED",
                        "批量回填失败",
                        failed_payload,
                        str(exc),
                    )
                )

    batch_update_task_result_states(success_rows + failed_rows)
    return {
        "accepted": True,
        "mode": "batch",
        "taskResultCount": len(task_results),
        "successCount": len(success_rows),
        "failedCount": len(failed_rows),
        "workerCount": worker_count,
        "cliId": cli_id,
    }


# 函数：process_word_clean_sentence_task_result
def process_word_clean_sentence_task_result(task_result_id: int, cli_id: str | None = None) -> dict[str, Any]:
    task_result = load_task_result_snapshot(task_result_id)
    payload = parse_task_result_payload(task_result)
    effective_cli_id = (cli_id or task_result.cli_id or "").strip()
    if not effective_cli_id:
        raise HTTPException(status_code=400, detail="任务结果未配置执行 CLI")

    cli_config = find_cli_config(effective_cli_id)
    access = cli_config.get("access", {})
    if not access.get("accessible"):
        raise HTTPException(status_code=400, detail=f"CLI 不可访问: {access.get('message', '')}")

    update_task_result_state(task_result.id, "RUNNING", "正在调用 CLI 评分", payload, "")
    try:
        cli_response = run_cli_prompt(cli_config, payload["aiPrompt"])
        ai_result = extract_json_payload(cli_response["stdout"])
        scores, best_score_item = validate_ai_score_result(ai_result, payload["writeBack"])
        connection_config = load_connection_config_snapshot(task_result.database_config_id)
        backfill_result = backfill_word_clean_sentence_score(
            connection_config,
            payload["writeBack"],
            scores,
            best_score_item,
            str(cli_config.get("label") or effective_cli_id),
        )
        completed_payload = {
            **payload,
            "aiRawOutput": cli_response["stdout"],
            "aiResult": {
                "scores": scores,
                "bestCandidate": best_score_item["candidate"],
            },
            "execution": {
                "cliId": effective_cli_id,
                "cliLabel": cli_config.get("label"),
                "command": cli_response["command"],
                "defaultArgs": cli_response["defaultArgs"],
                "effectiveArgs": cli_response["effectiveArgs"],
                "model": cli_response["model"],
                "reasoningEffort": cli_response["reasoningEffort"],
                "workingDirectory": cli_response["workingDirectory"],
                "returnCode": cli_response["returnCode"],
                "stderr": cli_response["stderr"],
                "processedAt": datetime.now(timezone.utc).isoformat(),
            },
            "backfillResult": backfill_result,
        }
        summary = (
            f"已评分 {len(scores)} 条候选句，最高分 "
            f"{best_score_item['candidate']}={best_score_item['score']}，TTS 待生成"
        )
        update_task_result_state(task_result.id, "SUCCESS", summary, completed_payload, "")
        return {
            "accepted": True,
            "taskResultId": task_result.id,
            "status": "SUCCESS",
            "summary": summary,
            "backfillResult": backfill_result,
        }
    except Exception as exc:
        failed_payload = {
            **payload,
            "processorError": str(exc),
            "processedAt": datetime.now(timezone.utc).isoformat(),
        }
        update_task_result_state(task_result.id, "FAILED", "评分或回填失败", failed_payload, str(exc))
        if isinstance(exc, HTTPException):
            raise
        raise HTTPException(status_code=500, detail=str(exc)) from exc


# 函数：load_queue_status_counts
def load_queue_status_counts() -> dict[str, int]:
    settings = load_database_settings()
    with connect_database(settings) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                select status, count(*)
                from tb_task_run
                where status in ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                group by status
                """
            )
            rows = cursor.fetchall()
    counts = {"QUEUED": 0, "RUNNING": 0, "RETRY_WAIT": 0}
    counts.update({str(row[0]): int(row[1]) for row in rows})
    return counts


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
    return {
        "status": "UP",
        "database": True,
        "queue": {
            **queue_runtime_state(),
            "statusCounts": load_queue_status_counts(),
        },
    }


# 函数：queue_status
@app.get("/api/queue/status")
def queue_status() -> dict[str, Any]:
    return {
        **queue_runtime_state(),
        "statusCounts": load_queue_status_counts(),
    }


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


# 函数：generate_results_from_task_config_simple
@app.post("/api/result-generation/from-task-config-simple")
def generate_results_from_task_config_simple(
    taskConfigId: int,
    overwrite: bool = False,
    onboardingGenerationId: str | None = None,
) -> dict[str, Any]:
    try:
        generation_id = normalize_onboarding_generation_id(onboardingGenerationId)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return generate_word_clean_sentence_results(taskConfigId, overwrite, generation_id)


# 函数：process_task_result_simple
@app.post("/api/task-result/process-simple")
def process_task_result_simple(
    taskResultId: int,
    cliId: str | None = None,
) -> dict[str, Any]:
    return process_word_clean_sentence_task_result(taskResultId, cliId)


# 函数：process_task_run_batch_json_simple
@app.post("/api/task-run/process-batch-json-simple")
def process_task_run_batch_json_simple(
    taskRunId: int,
    cliId: str | None = None,
) -> dict[str, Any]:
    return process_word_clean_sentence_task_run_batch(taskRunId, cliId)


# 函数：process_task_results_batch_simple
@app.post("/api/task-result/process-batch-simple")
def process_task_results_batch_simple(
    taskResultIds: str,
    cliId: str,
    workerCount: int = Query(default=4, ge=1, le=32),
) -> dict[str, Any]:
    task_result_ids = [int(item) for item in taskResultIds.split(",") if item.strip().isdigit()]
    return process_word_clean_sentence_task_results_batch(task_result_ids, cliId, workerCount)


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
