import importlib.util
import json
from pathlib import Path
from unittest.mock import Mock

import pytest


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_strict_dispatch", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(worker)


def config_snapshot(handler_key="task_config_42", executor_type="CLI", executor_id="codex"):
    return worker.TaskConfigSnapshot(
        id=42,
        task_name="测试任务",
        project_id=1,
        cli_id="legacy-cli",
        database_config_id=1,
        selected_tables=json.dumps(["public.unrelated_table"]),
        handler_key=handler_key,
        executor_type=executor_type,
        executor_id=executor_id,
    )


def run_snapshot(handler_key="task_config_42", executor_type="CLI", executor_id="codex"):
    return worker.TaskRunSnapshot(
        id=8,
        task_name="测试批次",
        cli_id="legacy-cli",
        ai_prompt_json=json.dumps({"taskType": worker.RESULT_MODE_TTS_BATCH}),
        ai_response_json="",
        record_type=worker.RECORD_TYPE_FORMAL,
        requested_worker_count=1,
        handler_key=handler_key,
        executor_type=executor_type,
        executor_id=executor_id,
    )


def result_snapshot(handler_key="task_config_42", executor_type="CLI", executor_id="codex"):
    return worker.TaskResultSnapshot(
        id=9,
        result_name="测试结果",
        task_config_id=42,
        project_id=1,
        cli_id="legacy-cli",
        database_config_id=1,
        status="PENDING",
        record_type=worker.RECORD_TYPE_VALIDATION_CURRENT,
        result_content=json.dumps({"taskType": worker.RESULT_MODE_TTS}),
        handler_key=handler_key,
        executor_type=executor_type,
        executor_id=executor_id,
    )


def registry_with(**callbacks):
    registry = worker.TaskHandlerRegistry()
    registry.register(worker.TaskHandlerDefinition(
        handler_key="task_config_42",
        required_capability="TEXT_GENERATION",
        **callbacks,
    ))
    return registry


def test_result_generation_uses_registered_handler(monkeypatch):
    callback = Mock(return_value={"insertedCount": 2})
    monkeypatch.setattr(worker, "TASK_HANDLER_REGISTRY", registry_with(result_generator=callback))
    monkeypatch.setattr(worker, "load_task_config_snapshot", lambda _: config_snapshot())

    assert worker.generate_results_for_task_config(42, False)["insertedCount"] == 2
    callback.assert_called_once_with(42, False, worker.RECORD_TYPE_FORMAL, None)


def test_single_validation_uses_registered_handler_and_target_snapshot(monkeypatch):
    callback = Mock(return_value={"status": "SUCCESS"})
    monkeypatch.setattr(worker, "TASK_HANDLER_REGISTRY", registry_with(single_processor=callback))
    monkeypatch.setattr(worker, "load_task_result_snapshot", lambda _: result_snapshot())

    worker.process_task_result_by_type(9, "ignored-legacy-cli")

    callback.assert_called_once_with(9, "codex")


def test_batch_execution_uses_registered_handler_and_target_snapshot(monkeypatch):
    callback = Mock(return_value={"status": "SUCCESS"})
    monkeypatch.setattr(worker, "TASK_HANDLER_REGISTRY", registry_with(batch_processor=callback))
    monkeypatch.setattr(worker, "load_task_run_snapshot", lambda _: run_snapshot())

    worker.process_task_run_batch_by_type(8, "ignored-legacy-cli")

    callback.assert_called_once_with(8, "codex")


@pytest.mark.parametrize("handler_key", ["", "missing"])
def test_missing_handler_never_falls_back_to_payload_or_tables(handler_key, monkeypatch):
    monkeypatch.setattr(worker, "TASK_HANDLER_REGISTRY", registry_with(batch_processor=Mock()))
    monkeypatch.setattr(worker, "load_task_run_snapshot", lambda _: run_snapshot(handler_key))

    with pytest.raises(worker.HTTPException, match="处理器"):
        worker.process_task_run_batch_by_type(8)


def test_incomplete_execution_snapshot_is_rejected(monkeypatch):
    monkeypatch.setattr(worker, "TASK_HANDLER_REGISTRY", registry_with(result_generator=Mock()))
    monkeypatch.setattr(
        worker,
        "load_task_config_snapshot",
        lambda _: config_snapshot(executor_type="CLI", executor_id=""),
    )

    with pytest.raises(worker.HTTPException, match="调用通道快照不完整"):
        worker.generate_results_for_task_config(42, False)


def test_unsupported_handler_phase_is_rejected(monkeypatch):
    monkeypatch.setattr(worker, "TASK_HANDLER_REGISTRY", registry_with())
    monkeypatch.setattr(worker, "load_task_result_snapshot", lambda _: result_snapshot())

    with pytest.raises(worker.HTTPException, match="不支持单条验证"):
        worker.process_task_result_by_type(9)
