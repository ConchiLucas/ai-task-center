import importlib.util
from pathlib import Path
from unittest.mock import Mock

import pytest


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_batch_prompt", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(worker)


def test_batch_prompt_calls_registered_handler_and_adds_metadata(monkeypatch):
    callback = Mock(return_value={"taskType": "custom_batch", "items": [{"itemKey": "item_A"}]})
    registry = worker.TaskHandlerRegistry()
    registry.register(worker.TaskHandlerDefinition(
        "task_config_42",
        "TEXT_GENERATION",
        batch_prompt_builder=callback,
    ))
    monkeypatch.setattr(worker, "TASK_HANDLER_REGISTRY", registry)
    request = worker.TaskHandlerBatchPromptRequest(
        taskConfigId=42,
        taskRunName="任务 42 - 批次 1",
        taskResultIds=[101, 102],
    )

    response = worker.build_task_handler_batch_prompt("task_config_42", request)

    assert response["_meta"] == {
        "handlerKey": "task_config_42",
        "taskConfigId": 42,
    }
    callback.assert_called_once_with(42, "任务 42 - 批次 1", [101, 102])


def test_batch_prompt_rejects_handler_without_builder(monkeypatch):
    registry = worker.TaskHandlerRegistry()
    registry.register(worker.TaskHandlerDefinition("task_config_42", "TEXT_GENERATION"))
    monkeypatch.setattr(worker, "TASK_HANDLER_REGISTRY", registry)
    request = worker.TaskHandlerBatchPromptRequest(
        taskConfigId=42,
        taskRunName="任务 42 - 批次 1",
        taskResultIds=[101],
    )

    with pytest.raises(worker.HTTPException, match="不支持批次构建"):
        worker.build_task_handler_batch_prompt("task_config_42", request)
