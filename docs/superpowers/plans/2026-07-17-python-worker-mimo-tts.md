# Python Worker MiMo TTS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move MiMo TTS execution from external word-agent into AI Task Center Python Worker.

**Architecture:** Extend the existing monolithic Worker module to reuse its PostgreSQL and urllib patterns. Resolve MiMo provider configuration per request, call Xiaomi directly, persist audio locally, and expose a FastAPI download endpoint.

**Tech Stack:** Python 3, FastAPI, psycopg2, urllib, unittest

---

### Task 1: Add database-backed MiMo configuration and generation tests

**Files:**
- Modify: `python-worker/tests/test_tts_batch_execution.py`
- Create: `python-worker/tests/test_mimo_tts_execution.py`

- [x] Add tests for snake-case database configuration, environment fallback, MiMo response decoding, and safe download filenames.
- [x] Run focused tests and verify they fail because the Worker still delegates to word-agent.

### Task 2: Implement direct MiMo TTS execution

**Files:**
- Modify: `python-worker/app/main.py`

- [x] Add focused configuration/result data structures and resolver functions.
- [x] Add direct MiMo request, response decoding, safe audio persistence, and download route.
- [x] Replace `post_word_agent_tts` calls and execution metadata with Python Worker execution.
- [x] Run focused tests and verify they pass.

### Task 3: Remove startup dependency and verify

**Files:**
- Modify: `scripts/start-dev.sh`
- Modify: `python-worker/tests/test_validation_execution.py`

- [x] Remove `WORD_AGENT_BASE_URL` injection and update existing tests.
- [x] Run all Python Worker tests.
- [x] Restart Python Worker and verify `/api/health` without invoking TTS.
- [x] Revert the superseded external word-agent changes and stop its temporary process.
- [x] Inspect final diffs and confirm no API Key is present.
