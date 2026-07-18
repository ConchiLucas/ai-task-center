import pytest

from app.handler_registry import TaskHandlerDefinition, TaskHandlerRegistry


def test_descriptor_is_safe_and_phase_flags_are_derived():
    registry = TaskHandlerRegistry()
    registry.register(TaskHandlerDefinition(
        handler_key="task_config_42",
        required_capability="text_generation",
        result_generator=lambda *args: {},
        single_processor=lambda *args: {},
        batch_prompt_builder=lambda *args: {},
        batch_processor=lambda *args: {},
    ))

    assert registry.describe("task_config_42") == {
        "handlerKey": "task_config_42",
        "requiredCapability": "TEXT_GENERATION",
        "supportsResultGeneration": True,
        "supportsSingleValidation": True,
        "supportsBatchBuild": True,
        "supportsBatchExecution": True,
    }


def test_registry_rejects_blank_duplicate_and_unknown_capability():
    registry = TaskHandlerRegistry()
    with pytest.raises(ValueError, match="Key 或能力无效"):
        registry.register(TaskHandlerDefinition("", "TEXT_GENERATION"))
    with pytest.raises(ValueError, match="Key 或能力无效"):
        registry.register(TaskHandlerDefinition("task_config_1", "IMAGE_GENERATION"))

    registry.register(TaskHandlerDefinition("task_config_1", "TEXT_GENERATION"))
    with pytest.raises(ValueError, match="重复注册"):
        registry.register(TaskHandlerDefinition("task_config_1", "TEXT_GENERATION"))


def test_unknown_handler_is_rejected():
    registry = TaskHandlerRegistry()

    with pytest.raises(ValueError, match="任务处理器未注册: missing"):
        registry.require("missing")
