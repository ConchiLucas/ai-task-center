#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${ROOT_DIR}/.runtime"
LOG_DIR="${RUNTIME_DIR}/logs"
PID_DIR="${RUNTIME_DIR}/pids"

HOST="${TASK_CENTER_HOST:-0.0.0.0}"
LOCAL_HOST="${TASK_CENTER_LOCAL_HOST:-127.0.0.1}"
BACKEND_PORT="${TASK_CENTER_SERVER_PORT:-18743}"
FRONTEND_PORT="${TASK_CENTER_FRONTEND_PORT:-19637}"
WORKER_PORT="${PYTHON_WORKER_PORT:-19186}"
QUEUE_MAX_WORKERS="${TASK_QUEUE_MAX_WORKERS:-8}"
QUEUE_POLL_SECONDS="${TASK_QUEUE_POLL_SECONDS:-1}"
QUEUE_LEASE_SECONDS="${TASK_QUEUE_LEASE_SECONDS:-900}"
QUEUE_HEARTBEAT_SECONDS="${TASK_QUEUE_HEARTBEAT_SECONDS:-30}"

DB_HOST="${TASK_CENTER_DB_HOST:-127.0.0.1}"
DB_PORT="${TASK_CENTER_DB_PORT:-5432}"
DB_NAME="${TASK_CENTER_DB_NAME:-ai_task_center}"
DB_USER="${TASK_CENTER_DB_USER:-conchi}"
DB_PASSWORD="${TASK_CENTER_DB_PASSWORD:-conchi123456}"
DB_URL="${TASK_CENTER_DB_URL:-jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}}"
PYTHON_WORKER_BASE_URL="${PYTHON_WORKER_BASE_URL:-http://${LOCAL_HOST}:${WORKER_PORT}}"
WORD_AGENT_BASE_URL="${WORD_AGENT_BASE_URL:-http://${LOCAL_HOST}:8010}"
MAVEN_BIN="${MAVEN_BIN:-$(command -v mvn || true)}"
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
BACKEND_JAR="${ROOT_DIR}/target/ai-task-center-0.0.1-SNAPSHOT.jar"
if [ -z "$MAVEN_BIN" ] && [ -x "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" ]; then
  MAVEN_BIN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
fi
USE_LAUNCHCTL=false
if [ "$(uname -s)" = "Darwin" ] && command -v launchctl >/dev/null 2>&1; then
  USE_LAUNCHCTL=true
fi

BACKEND_URL="http://${LOCAL_HOST}:${BACKEND_PORT}/api/task-run/list"
FRONTEND_URL="http://${LOCAL_HOST}:${FRONTEND_PORT}/"
WORKER_URL="http://${LOCAL_HOST}:${WORKER_PORT}/api/health"

mkdir -p "${LOG_DIR}" "${PID_DIR}"

# 函数：print_section
print_section() {
  printf '\n==> %s\n' "$1"
}

# 函数：is_http_up
is_http_up() {
  local url="$1"
  curl -fsS --max-time 2 "$url" >/dev/null 2>&1
}

# 函数：wait_http
wait_http() {
  local name="$1"
  local url="$2"
  local log_file="$3"

  for _ in $(seq 1 60); do
    if is_http_up "$url"; then
      printf '%s ready: %s\n' "$name" "$url"
      return 0
    fi
    sleep 1
  done

  printf '%s failed to start. Last log lines:\n' "$name" >&2
  tail -n 80 "$log_file" >&2 || true
  return 1
}

# 函数：ensure_postgres
ensure_postgres() {
  print_section "Checking PostgreSQL"
  if nc -z "$DB_HOST" "$DB_PORT"; then
    printf 'PostgreSQL ready: %s:%s/%s\n' "$DB_HOST" "$DB_PORT" "$DB_NAME"
    return 0
  fi

  printf 'PostgreSQL is not reachable at %s:%s.\n' "$DB_HOST" "$DB_PORT" >&2
  printf 'Please start your existing local PostgreSQL first. This script will not start Docker PostgreSQL.\n' >&2
  return 1
}

# 函数：ensure_python_worker_dependencies
ensure_python_worker_dependencies() {
  local worker_dir="${ROOT_DIR}/python-worker"
  if [ ! -x "${worker_dir}/.venv/bin/uvicorn" ]; then
    print_section "Installing Python Worker dependencies"
    python3 -m venv "${worker_dir}/.venv"
    "${worker_dir}/.venv/bin/pip" install -r "${worker_dir}/requirements.txt"
  fi
}

# 函数：ensure_frontend_dependencies
ensure_frontend_dependencies() {
  local frontend_dir="${ROOT_DIR}/web-react"
  if [ ! -d "${frontend_dir}/node_modules" ]; then
    print_section "Installing React dependencies"
    (cd "$frontend_dir" && npm install)
  fi
}

# 函数：start_backend
start_backend() {
  local log_file="${LOG_DIR}/backend.log"

  print_section "Starting Java backend"
  if is_http_up "$BACKEND_URL"; then
    printf 'Java backend already running: %s\n' "$BACKEND_URL"
    return 0
  fi
  if [ -z "$MAVEN_BIN" ]; then
    printf 'Maven executable was not found. Set MAVEN_BIN before starting.\n' >&2
    return 1
  fi
  if [ -z "$JAVA_BIN" ]; then
    printf 'Java executable was not found. Set JAVA_BIN before starting.\n' >&2
    return 1
  fi

  "$MAVEN_BIN" -q -DskipTests package

  if [ "$USE_LAUNCHCTL" = true ]; then
    launchctl remove com.conchi.ai-task-center.backend >/dev/null 2>&1 || true
    launchctl submit -l com.conchi.ai-task-center.backend -- /bin/zsh -lc \
      "cd '$ROOT_DIR' && exec env TASK_CENTER_SERVER_PORT='$BACKEND_PORT' TASK_CENTER_DB_URL='$DB_URL' TASK_CENTER_DB_USER='$DB_USER' TASK_CENTER_DB_PASSWORD='$DB_PASSWORD' PYTHON_WORKER_BASE_URL='$PYTHON_WORKER_BASE_URL' '$JAVA_BIN' -jar '$BACKEND_JAR' >> '$log_file' 2>&1"
  else
    (
      cd "$ROOT_DIR"
      nohup env \
        TASK_CENTER_SERVER_PORT="$BACKEND_PORT" \
        TASK_CENTER_DB_URL="$DB_URL" \
        TASK_CENTER_DB_USER="$DB_USER" \
        TASK_CENTER_DB_PASSWORD="$DB_PASSWORD" \
        PYTHON_WORKER_BASE_URL="$PYTHON_WORKER_BASE_URL" \
        "$JAVA_BIN" -jar "$BACKEND_JAR" >"$log_file" 2>&1 &
      echo $! >"${PID_DIR}/backend.pid"
    )
  fi

  wait_http "Java backend" "$BACKEND_URL" "$log_file"
}

# 函数：start_python_worker
start_python_worker() {
  local worker_dir="${ROOT_DIR}/python-worker"
  local log_file="${LOG_DIR}/python-worker.log"

  print_section "Starting Python Worker"
  if is_http_up "$WORKER_URL"; then
    printf 'Python Worker already running: %s\n' "$WORKER_URL"
    return 0
  fi

  ensure_python_worker_dependencies
  if [ "$USE_LAUNCHCTL" = true ]; then
    launchctl remove com.conchi.ai-task-center.python-worker >/dev/null 2>&1 || true
    launchctl submit -l com.conchi.ai-task-center.python-worker -- /bin/zsh -lc \
      "cd '$worker_dir' && exec env TASK_CENTER_DB_HOST='$DB_HOST' TASK_CENTER_DB_PORT='$DB_PORT' TASK_CENTER_DB_NAME='$DB_NAME' TASK_CENTER_DB_USER='$DB_USER' TASK_CENTER_DB_PASSWORD='$DB_PASSWORD' TASK_QUEUE_MAX_WORKERS='$QUEUE_MAX_WORKERS' TASK_QUEUE_POLL_SECONDS='$QUEUE_POLL_SECONDS' TASK_QUEUE_LEASE_SECONDS='$QUEUE_LEASE_SECONDS' TASK_QUEUE_HEARTBEAT_SECONDS='$QUEUE_HEARTBEAT_SECONDS' WORD_AGENT_BASE_URL='$WORD_AGENT_BASE_URL' .venv/bin/uvicorn app.main:app --host '$HOST' --port '$WORKER_PORT' >> '$log_file' 2>&1"
  else
    (
      cd "$worker_dir"
      nohup env \
        TASK_CENTER_DB_HOST="$DB_HOST" \
        TASK_CENTER_DB_PORT="$DB_PORT" \
        TASK_CENTER_DB_NAME="$DB_NAME" \
        TASK_CENTER_DB_USER="$DB_USER" \
        TASK_CENTER_DB_PASSWORD="$DB_PASSWORD" \
        TASK_QUEUE_MAX_WORKERS="$QUEUE_MAX_WORKERS" \
        TASK_QUEUE_POLL_SECONDS="$QUEUE_POLL_SECONDS" \
        TASK_QUEUE_LEASE_SECONDS="$QUEUE_LEASE_SECONDS" \
        TASK_QUEUE_HEARTBEAT_SECONDS="$QUEUE_HEARTBEAT_SECONDS" \
        WORD_AGENT_BASE_URL="$WORD_AGENT_BASE_URL" \
        .venv/bin/uvicorn app.main:app --host "$HOST" --port "$WORKER_PORT" >"$log_file" 2>&1 &
      echo $! >"${PID_DIR}/python-worker.pid"
    )
  fi

  wait_http "Python Worker" "$WORKER_URL" "$log_file"
}

# 函数：start_frontend
start_frontend() {
  local frontend_dir="${ROOT_DIR}/web-react"
  local log_file="${LOG_DIR}/frontend.log"

  print_section "Starting React frontend"
  if is_http_up "$FRONTEND_URL"; then
    printf 'React frontend already running: %s\n' "$FRONTEND_URL"
    return 0
  fi

  ensure_frontend_dependencies
  if [ "$USE_LAUNCHCTL" = true ]; then
    launchctl remove com.conchi.ai-task-center.frontend >/dev/null 2>&1 || true
    launchctl submit -l com.conchi.ai-task-center.frontend -- /bin/zsh -lc \
      "cd '$frontend_dir' && exec npm run dev -- --host '$HOST' --port '$FRONTEND_PORT' >> '$log_file' 2>&1"
  else
    (
      cd "$frontend_dir"
      nohup npm run dev -- --host "$HOST" --port "$FRONTEND_PORT" >"$log_file" 2>&1 &
      echo $! >"${PID_DIR}/frontend.pid"
    )
  fi

  wait_http "React frontend" "$FRONTEND_URL" "$log_file"
}

# 函数：print_summary
print_summary() {
  print_section "AI Task Center is ready"
  printf 'Frontend:      %s\n' "$FRONTEND_URL"
  printf 'Java backend:  http://%s:%s\n' "$LOCAL_HOST" "$BACKEND_PORT"
  printf 'Python Worker: http://%s:%s\n' "$LOCAL_HOST" "$WORKER_PORT"
  printf 'Logs:          %s\n' "$LOG_DIR"
}

ensure_postgres
start_backend
start_python_worker
start_frontend
print_summary
