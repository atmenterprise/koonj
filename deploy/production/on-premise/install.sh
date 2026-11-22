#!/usr/bin/env bash
# =============================================================================
# Koonj — On-Premise Installer (Linux / macOS)
# =============================================================================
# Usage (run as root):
#   sudo ./install.sh                       # install to /opt/koonj
#   sudo ./install.sh --prefix /srv/koonj   # custom path
#   sudo ./install.sh --systemd             # install systemd unit
#   sudo ./install.sh --nginx               # install nginx config
#   sudo ./install.sh --uninstall           # remove
# =============================================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

INSTALL_PREFIX="/opt/koonj"
INSTALL_SYSTEMD=false
INSTALL_NGINX=false
UNINSTALL=false
KOONJ_USER="koonj"
KOONJ_GROUP="koonj"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix)    INSTALL_PREFIX="$2"; shift 2 ;;
    --systemd)   INSTALL_SYSTEMD=true; shift ;;
    --nginx)     INSTALL_NGINX=true; shift ;;
    --uninstall) UNINSTALL=true; shift ;;
    --help|-h)   head -11 "$0" | tail -7; exit 0 ;;
    *) echo "Unknown: $1"; exit 1 ;;
  esac
done

if ${UNINSTALL}; then
  echo "Uninstalling Koonj …"
  systemctl is-active --quiet koonj-server 2>/dev/null && systemctl stop koonj-server
  systemctl disable koonj-server 2>/dev/null || true
  rm -f /etc/systemd/system/koonj-server.service /etc/nginx/conf.d/koonj.conf
  systemctl daemon-reload 2>/dev/null || true
  echo "Done. ${INSTALL_PREFIX} was NOT removed — delete manually if needed."
  exit 0
fi

[[ "$(id -u)" -eq 0 ]] || { echo "ERROR: Run as root (sudo)."; exit 1; }
command -v java >/dev/null 2>&1 || { echo "ERROR: Java 21+ required."; exit 1; }

JAVA_VER=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f1)
[[ "${JAVA_VER}" -ge 21 ]] || { echo "ERROR: Java 21+ required, found ${JAVA_VER}."; exit 1; }

echo "============================================================"
echo "  Koonj Installer → ${INSTALL_PREFIX}"
echo "============================================================"

# Create user
if ! id "${KOONJ_USER}" &>/dev/null; then
  echo "▸ Creating user '${KOONJ_USER}' …"
  useradd --system --no-create-home --shell /usr/sbin/nologin \
    --home-dir "${INSTALL_PREFIX}" "${KOONJ_USER}" 2>/dev/null || true
fi

echo "▸ Creating directories …"
mkdir -p "${INSTALL_PREFIX}"/{bin,config,lib,web,logs,keycloak}

echo "▸ Copying files …"
[[ -f "${SCRIPT_DIR}/lib/koonj-server.jar" ]] && cp "${SCRIPT_DIR}/lib/koonj-server.jar" "${INSTALL_PREFIX}/lib/"
[[ -d "${SCRIPT_DIR}/web" ]] && cp -r "${SCRIPT_DIR}/web/." "${INSTALL_PREFIX}/web/"
cp "${SCRIPT_DIR}/bin/koonj-server.sh"  "${INSTALL_PREFIX}/bin/"
cp "${SCRIPT_DIR}/bin/koonj-server.cmd" "${INSTALL_PREFIX}/bin/" 2>/dev/null || true
if [[ -f "${SCRIPT_DIR}/lib/koonj-fuse.jar" ]]; then
  cp "${SCRIPT_DIR}/lib/koonj-fuse.jar" "${INSTALL_PREFIX}/lib/"
  cp "${SCRIPT_DIR}/bin/run-koonj-fuse.sh" "${SCRIPT_DIR}/bin/run-koonj-fuse.ps1" "${INSTALL_PREFIX}/bin/"
fi
chmod +x "${INSTALL_PREFIX}/bin/"*.sh

[[ -f "${INSTALL_PREFIX}/config/koonj.env" ]] \
  && echo "  ⊘ koonj.env exists — skipped" \
  || cp "${SCRIPT_DIR}/config/koonj.env" "${INSTALL_PREFIX}/config/"

[[ -f "${INSTALL_PREFIX}/config/application-production.yml" ]] \
  && echo "  ⊘ application-production.yml exists — skipped" \
  || cp "${SCRIPT_DIR}/config/application-production.yml" "${INSTALL_PREFIX}/config/"

cp "${SCRIPT_DIR}/keycloak/"* "${INSTALL_PREFIX}/keycloak/" 2>/dev/null || true

chown -R "${KOONJ_USER}:${KOONJ_GROUP}" "${INSTALL_PREFIX}" 2>/dev/null \
  || chown -R "${KOONJ_USER}" "${INSTALL_PREFIX}"

if ${INSTALL_SYSTEMD}; then
  echo "▸ Installing systemd service …"
  cp "${SCRIPT_DIR}/systemd/koonj-server.service" /etc/systemd/system/
  [[ "${INSTALL_PREFIX}" != "/opt/koonj" ]] && \
    sed -i "s|/opt/koonj|${INSTALL_PREFIX}|g" /etc/systemd/system/koonj-server.service
  systemctl daemon-reload; systemctl enable koonj-server
  echo "  ✓ Enabled. Start with: sudo systemctl start koonj-server"
fi

if ${INSTALL_NGINX}; then
  echo "▸ Installing Nginx config …"
  if command -v nginx >/dev/null 2>&1 && [[ -f "${SCRIPT_DIR}/nginx/koonj.conf" ]]; then
    cp "${SCRIPT_DIR}/nginx/koonj.conf" /etc/nginx/conf.d/koonj.conf
    [[ "${INSTALL_PREFIX}" != "/opt/koonj" ]] && \
      sed -i "s|/opt/koonj|${INSTALL_PREFIX}|g" /etc/nginx/conf.d/koonj.conf
    nginx -t && systemctl reload nginx && echo "  ✓ Nginx reloaded."
  else
    echo "  WARNING: Nginx not found or config missing."
  fi
fi

echo ""
echo "============================================================"
echo "  Installation complete!"
echo "============================================================"
echo "  Next: edit ${INSTALL_PREFIX}/config/koonj.env then start."
echo ""
