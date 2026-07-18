import importlib.util
from pathlib import Path
from unittest.mock import Mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "app" / "main.py"
SPEC = importlib.util.spec_from_file_location("ai_task_center_worker_strict_snapshots", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(worker)


def claim():
    return worker.QueuedTaskClaim(
        id=31,
        attempt_no=1,
        max_attempts=3,
        claim_token="claim-token",
        requested_worker_count=1,
        execution_mode="thread",
        dispatch_group_id="group-1",
    )


def test_queue_does_not_pass_legacy_cli(monkeypatch):
    process = Mock(return_value={"successCount": 1, "failedCount": 0})
    finish = Mock(return_value=True)
    monkeypatch.setattr(worker, "process_task_run_batch_by_type", process)
    monkeypatch.setattr(worker, "finish_queued_task", finish)

    worker.execute_queued_task(claim())

    process.assert_called_once_with(31)
    finish.assert_called_once()
