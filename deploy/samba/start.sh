#!/bin/sh
set -eu

: "${KOONJ_SMB_PASSWORD:?Set KOONJ_SMB_PASSWORD in the gateway environment}"
config=/etc/koonj-smb/smb.conf

mounted() {
    [ "$(timeout 5 stat -f -c %t /mnt/koonj-share 2>/dev/null)" = 65735546 ]
}

# Docker can restart the sidecar before the gateway has mounted the share.
for attempt in $(seq 1 30); do
    if mounted; then break; fi
    sleep 2
done
if ! mounted; then
    echo "Waiting for a live Koonj FUSE mount before exporting the share." >&2
    exit 1
fi

if ! id koonj >/dev/null 2>&1; then
    useradd -M -s /sbin/nologin koonj
fi
printf '%s\n%s\n' "$KOONJ_SMB_PASSWORD" "$KOONJ_SMB_PASSWORD" | smbpasswd -c "$config" -s -a koonj

smbd --foreground --no-process-group --debug-stdout --configfile "$config" &
smbd_pid=$!

stop_smbd() {
    kill "$smbd_pid" 2>/dev/null || true
    wait "$smbd_pid" 2>/dev/null || true
}
trap 'stop_smbd; exit 0' INT TERM

while kill -0 "$smbd_pid" 2>/dev/null; do
    sleep 5
    if ! mounted; then
        echo "Koonj FUSE mount disconnected. Restarting the SMB sidecar to rebind it." >&2
        stop_smbd
        exit 1
    fi
done
wait "$smbd_pid"
