import importlib.util
import json
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_catalog", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(worker)


def test_catalog_exposes_safe_registered_descriptors_only():
    payload = worker.list_task_handlers()

    assert payload["count"] == 3
    assert {item["handlerKey"] for item in payload["handlers"]} == {
        worker.HANDLER_SCORE,
        worker.HANDLER_TTS,
        worker.TASK_CONFIG_4_HANDLER,
    }
    serialized = json.dumps(payload).lower()
    assert "api_key" not in serialized
    assert "<function" not in serialized


def test_catalog_returns_one_handler_or_404():
    descriptor = worker.get_task_handler(worker.HANDLER_TTS)
    assert descriptor["requiredCapability"] == "AUDIO_TTS"
    assert descriptor["supportsResultGeneration"] is True
    assert descriptor["supportsSingleValidation"] is True
    assert descriptor["supportsBatchExecution"] is True

    try:
        worker.get_task_handler("missing")
    except worker.HTTPException as exc:
        assert exc.status_code == 404
        assert exc.detail == "任务处理器未注册: missing"
    else:
        raise AssertionError("missing handler should return HTTP 404")
