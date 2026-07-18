from collections.abc import Callable
from dataclasses import dataclass, replace
from typing import Any


SUPPORTED_CAPABILITIES = {"TEXT_GENERATION", "AUDIO_TTS"}


@dataclass(frozen=True)
class TaskHandlerDefinition:
    handler_key: str
    required_capability: str
    result_generator: Callable[..., dict[str, Any]] | None = None
    single_processor: Callable[..., dict[str, Any]] | None = None
    batch_prompt_builder: Callable[..., dict[str, Any]] | None = None
    batch_processor: Callable[..., dict[str, Any]] | None = None


def handler_descriptor(definition: TaskHandlerDefinition) -> dict[str, Any]:
    return {
        "handlerKey": definition.handler_key,
        "requiredCapability": definition.required_capability,
        "supportsResultGeneration": definition.result_generator is not None,
        "supportsSingleValidation": definition.single_processor is not None,
        "supportsBatchBuild": definition.batch_prompt_builder is not None,
        "supportsBatchExecution": definition.batch_processor is not None,
    }


class TaskHandlerRegistry:
    def __init__(self) -> None:
        self._handlers: dict[str, TaskHandlerDefinition] = {}

    def register(self, definition: TaskHandlerDefinition) -> None:
        key = str(definition.handler_key or "").strip()
        capability = str(definition.required_capability or "").strip().upper()
        if not key or capability not in SUPPORTED_CAPABILITIES:
            raise ValueError("任务处理器 Key 或能力无效")
        if key in self._handlers:
            raise ValueError(f"任务处理器重复注册: {key}")
        self._handlers[key] = replace(
            definition,
            handler_key=key,
            required_capability=capability,
        )

    def require(self, handler_key: str) -> TaskHandlerDefinition:
        key = str(handler_key or "").strip()
        if not key or key not in self._handlers:
            raise ValueError(f"任务处理器未注册: {key or '空'}")
        return self._handlers[key]

    def describe(self, handler_key: str) -> dict[str, Any]:
        return handler_descriptor(self.require(handler_key))

    def list_descriptors(self) -> list[dict[str, Any]]:
        return [handler_descriptor(self._handlers[key]) for key in sorted(self._handlers)]
