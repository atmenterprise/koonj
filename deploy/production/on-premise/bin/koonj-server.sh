#!/usr/bin/env bash
# =============================================================================
# Koonj Server — Service Management Script (Linux / macOS)
# =============================================================================
# Usage:
#   koonj-server.sh start|stop|restart|status|logs
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOONJ_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ── Load configuration ──────────────────────────────────────────────────────
ENV_FILE="${KOONJ_HOME}/config/koonj.env"
if [[ -f "${ENV_FILE}" ]]; then
  set -a; source "${ENV_FILE}"; set +a
fi

KOONJ_JAR="${KOONJ_HOME}/lib/koonj-server.jar"
KOONJ_LOG_DIR="${KOONJ_HOME}/logs"
KOONJ_PID_FILE="${KOONJ_LOG_DIR}/koonj-server.pid"
KOONJ_CONSOLE_LOG="${KOONJ_LOG_DIR}/koonj-server-console.log"

JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom}"
SPRING_PROFILES="${SPRING_PROFILES_ACTIVE:-production}"
SERVER_PORT="${KOONJ_SERVER_PORT:-8080}"

SPRING_CONFIG="${KOONJ_HOME}/config/application-production.yml"
SPRING_CONFIG_FLAG=""
[[ -f "${SPRING_CONFIG}" ]] && SPRING_CONFIG_FLAG="--spring.config.additional-location=file:${SPRING_CONFIG}"

mkdir -p "${KOONJ_LOG_DIR}"

get_pid() {
  if [[ -f "${KOONJ_PID_FILE}" ]]; then
    local pid; pid=$(cat "${KOONJ_PID_FILE}")
    if kill -0 "${pid}" 2>/dev/null; then echo "${pid}"; return 0; fi
    rm -f "${KOONJ_PID_FILE}"
  fi
  return 1
}

do_start() {
  if pid=$(get_pid); then
    echo "Koonj Server is already running (PID ${pid})."; return 0
  fi
  [[ -f "${KOONJ_JAR}" ]] || { echo "ERROR: JAR not found at ${KOONJ_JAR}"; exit 1; }
  echo "Starting Koonj Server …"
  nohup java ${JAVA_OPTS} \
    -Dspring.profiles.active="${SPRING_PROFILES}" \
    -Dserver.port="${SERVER_PORT}" \
    -jar "${KOONJ_JAR}" ${SPRING_CONFIG_FLAG} \
    >> "${KOONJ_CONSOLE_LOG}" 2>&1 &
  echo $! > "${KOONJ_PID_FILE}"
  echo "  ✓ Started (PID $(cat "${KOONJ_PID_FILE}"))  Log: ${KOONJ_CONSOLE_LOG}"
}

do_stop() {
  if pid=$(get_pid); then
    echo "Stopping Koonj Server (PID ${pid}) …"
    kill "${pid}"
    for _ in $(seq 1 30); do kill -0 "${pid}" 2>/dev/null || break; sleep 1; done
    kill -0 "${pid}" 2>/dev/null && { kill -9 "${pid}" 2>/dev/null || true; }
    rm -f "${KOONJ_PID_FILE}"; echo "  ✓ Stopped."
  else
    echo "Koonj Server is not running."
  fi
}

do_status() {
  if pid=$(get_pid); then echo "Koonj Server is running (PID ${pid}).";
  else echo "Koonj Server is not running."; return 1; fi
}

do_logs() {
  [[ -f "${KOONJ_CONSOLE_LOG}" ]] && tail -f "${KOONJ_CONSOLE_LOG}" || echo "No log file."
}

case "${1:-}" in
  start)   do_start ;;
  stop)    do_stop ;;
  restart) do_stop; sleep 2; do_start ;;
  status)  do_status ;;
  logs)    do_logs ;;
  *) echo "Usage: $(basename "$0") {start|stop|restart|status|logs}"; exit 1 ;;
esac
