#!/usr/bin/env bash
# =============================================================================
# Koonj — Master Build & Package Script (Linux / macOS)
# =============================================================================
# Builds both koonj-server (Maven) and koonj-ui (npm) and assembles a
# distributable archive for on-premise or Docker deployment.
#
# Usage:
#   ./build.sh                        # build everything
#   ./build.sh --target on-premise    # on-premise package only
#   ./build.sh --target docker        # docker package only
#   ./build.sh --skip-tests           # skip Maven tests
#   ./build.sh --version 1.0.0        # override version label
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# ── Defaults ─────────────────────────────────────────────────────────────────
TARGET="all"            # all | on-premise | docker
SKIP_TESTS=false
VERSION=""
DIST_DIR="${PROJECT_ROOT}/dist"

# ── Parse arguments ──────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)      TARGET="$2";      shift 2 ;;
    --skip-tests)  SKIP_TESTS=true;  shift   ;;
    --version)     VERSION="$2";     shift 2 ;;
    --help|-h)
      head -14 "$0" | tail -8
      exit 0
      ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# ── Resolve version ──────────────────────────────────────────────────────────
if [[ -z "${VERSION}" ]]; then
  VERSION=$(mvn -f "${PROJECT_ROOT}/pom.xml" help:evaluate \
    -Dexpression=project.version -q -DforceStdout 2>/dev/null \
    || echo "0.1.0-SNAPSHOT")
fi

echo "============================================================"
echo "  Koonj Build — v${VERSION}"
echo "  Target: ${TARGET}"
echo "============================================================"

# ── Pre-flight checks ───────────────────────────────────────────────────────
command -v java  >/dev/null 2>&1 || { echo "ERROR: Java 21+ is required.";  exit 1; }
command -v mvn   >/dev/null 2>&1 || { echo "ERROR: Maven 3.9+ is required."; exit 1; }
command -v node  >/dev/null 2>&1 || { echo "ERROR: Node.js 20+ is required."; exit 1; }
command -v npm   >/dev/null 2>&1 || { echo "ERROR: npm is required.";        exit 1; }

JAVA_VER=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f1)
if [[ "${JAVA_VER}" -lt 21 ]]; then
  echo "ERROR: Java 21+ required, found Java ${JAVA_VER}."
  exit 1
fi

# ── Clean previous output ───────────────────────────────────────────────────
rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"

# ── Step 1: Build koonj-server ───────────────────────────────────────────────
echo ""
echo "▸ Building koonj-server …"
MVN_OPTS="-B -ntp"
if ${SKIP_TESTS}; then
  MVN_OPTS="${MVN_OPTS} -DskipTests"
fi

(cd "${PROJECT_ROOT}" && mvn ${MVN_OPTS} -pl koonj-server -am clean package)

SERVER_JAR=$(find "${PROJECT_ROOT}/koonj-server/target" -maxdepth 1 \
  -name 'koonj-server-*.jar' ! -name '*-original*' ! -name '*-sources*' \
  | head -1)

if [[ ! -f "${SERVER_JAR}" ]]; then
  echo "ERROR: Server JAR not found after build."
  exit 1
fi
echo "  ✓ Server JAR: $(basename "${SERVER_JAR}")"

# ── Step 2: Build koonj-ui ──────────────────────────────────────────────────
echo ""
echo "▸ Building koonj-ui …"
(cd "${PROJECT_ROOT}/koonj-ui" && npm ci --no-audit --no-fund && npm run build)

UI_DIST="${PROJECT_ROOT}/koonj-ui/dist"
if [[ ! -d "${UI_DIST}" ]]; then
  echo "ERROR: UI dist directory not found after build."
  exit 1
fi
echo "  ✓ UI dist: $(du -sh "${UI_DIST}" | awk '{print $1}')"

# ── Step 3: Assemble on-premise distribution ─────────────────────────────────
assemble_on_premise() {
  echo ""
  echo "▸ Assembling on-premise distribution …"
  local OP_DIR="${DIST_DIR}/koonj-${VERSION}-on-premise"
  mkdir -p "${OP_DIR}"/{bin,config,lib,web,systemd,nginx}

  # Server JAR
  cp "${SERVER_JAR}" "${OP_DIR}/lib/koonj-server.jar"

  # UI static assets
  cp -r "${UI_DIST}/." "${OP_DIR}/web/"

  # Scripts & configs from deploy/production/on-premise
  cp "${SCRIPT_DIR}/on-premise/bin/koonj-server.sh"      "${OP_DIR}/bin/"
  cp "${SCRIPT_DIR}/on-premise/bin/koonj-server.cmd"     "${OP_DIR}/bin/"
  cp "${SCRIPT_DIR}/on-premise/install.sh"               "${OP_DIR}/"
  cp "${SCRIPT_DIR}/on-premise/install.cmd"              "${OP_DIR}/"
  cp "${SCRIPT_DIR}/on-premise/config/koonj.env.example" "${OP_DIR}/config/koonj.env"
  cp "${SCRIPT_DIR}/on-premise/config/application-production.yml" \
     "${OP_DIR}/config/"
  cp "${SCRIPT_DIR}/on-premise/systemd/koonj-server.service" "${OP_DIR}/systemd/"
  cp "${SCRIPT_DIR}/on-premise/nginx/koonj.conf"         "${OP_DIR}/nginx/"

  # Keycloak realm template
  mkdir -p "${OP_DIR}/keycloak"
  cp "${PROJECT_ROOT}/deploy/keycloak/koonj-realm.json"  "${OP_DIR}/keycloak/"

  # Docs
  if [[ -f "${PROJECT_ROOT}/docs/DEPLOYMENT-GUIDE.md" ]]; then
    cp "${PROJECT_ROOT}/docs/DEPLOYMENT-GUIDE.md" "${OP_DIR}/"
  fi

  chmod +x "${OP_DIR}/bin/"*.sh "${OP_DIR}/install.sh" 2>/dev/null || true

  # Create tarball
  (cd "${DIST_DIR}" && tar czf "koonj-${VERSION}-on-premise.tar.gz" \
    "koonj-${VERSION}-on-premise")
  echo "  ✓ Archive: dist/koonj-${VERSION}-on-premise.tar.gz"

  # Create zip for Windows customers
  if command -v zip >/dev/null 2>&1; then
    (cd "${DIST_DIR}" && zip -qr "koonj-${VERSION}-on-premise.zip" \
      "koonj-${VERSION}-on-premise")
    echo "  ✓ Archive: dist/koonj-${VERSION}-on-premise.zip"
  fi
}

# ── Step 4: Assemble Docker distribution ─────────────────────────────────────
assemble_docker() {
  echo ""
  echo "▸ Assembling Docker distribution …"
  local DK_DIR="${DIST_DIR}/koonj-${VERSION}-docker"
  mkdir -p "${DK_DIR}"/{server,ui,nginx,keycloak,config}

  # Pre-built artifacts for Docker COPY (no multi-stage build needed by customer)
  cp "${SERVER_JAR}" "${DK_DIR}/server/koonj-server.jar"
  cp -r "${UI_DIST}/." "${DK_DIR}/ui/"

  # Docker files
  cp "${SCRIPT_DIR}/docker/Dockerfile.server"              "${DK_DIR}/server/"
  cp "${SCRIPT_DIR}/docker/Dockerfile.ui"                  "${DK_DIR}/ui/"
  cp "${SCRIPT_DIR}/docker/nginx/default.conf.template"    "${DK_DIR}/ui/"
  cp "${SCRIPT_DIR}/docker/nginx/default.conf.template"    "${DK_DIR}/nginx/"
  cp "${SCRIPT_DIR}/docker/docker-compose.yml"             "${DK_DIR}/"
  cp "${SCRIPT_DIR}/docker/.env.example"                   "${DK_DIR}/.env"

  # Keycloak realm
  cp "${PROJECT_ROOT}/deploy/keycloak/koonj-realm.json"    "${DK_DIR}/keycloak/"
  cp "${PROJECT_ROOT}/deploy/keycloak/init-db.sql"         "${DK_DIR}/keycloak/"

  # Docs
  if [[ -f "${PROJECT_ROOT}/docs/DEPLOYMENT-GUIDE.md" ]]; then
    cp "${PROJECT_ROOT}/docs/DEPLOYMENT-GUIDE.md" "${DK_DIR}/"
  fi

  # Create tarball
  (cd "${DIST_DIR}" && tar czf "koonj-${VERSION}-docker.tar.gz" \
    "koonj-${VERSION}-docker")
  echo "  ✓ Archive: dist/koonj-${VERSION}-docker.tar.gz"
}

# ── Execute assembly ─────────────────────────────────────────────────────────
case "${TARGET}" in
  all)
    assemble_on_premise
    assemble_docker
    ;;
  on-premise)
    assemble_on_premise
    ;;
  docker)
    assemble_docker
    ;;
  *)
    echo "ERROR: Unknown target '${TARGET}'. Use: all, on-premise, docker"
    exit 1
    ;;
esac

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "============================================================"
echo "  Build complete!"
echo "  Output: ${DIST_DIR}/"
echo "============================================================"
ls -lh "${DIST_DIR}"/*.tar.gz "${DIST_DIR}"/*.zip 2>/dev/null || true
echo ""
