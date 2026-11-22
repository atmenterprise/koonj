#!/usr/bin/env bash
set -euo pipefail

if [[ $(uname -s) != Linux || $(id -u) != 0 ]]; then
    printf 'Run this script with sudo on the Linux Docker host.\n' >&2
    exit 1
fi

backing=${KOONJ_FUSE_BACKING_DIR:-/srv/koonj/backing}
share=${KOONJ_FUSE_MOUNT_POINT:-/srv/koonj/share}
if [[ $backing != /* || $share != /* ]]; then
    printf 'The backing directory and mount point must be absolute host paths.\n' >&2
    exit 1
fi
backing=$(realpath -m -- "$backing")
share=$(realpath -m -- "$share")
if [[ $backing == / || $share == / || $backing == "$share" || $backing == "$share/"* || $share == "$backing/"* ]]; then
    printf 'Choose separate backing and mount directories with no overlap.\n' >&2
    exit 1
fi

if ! command -v fusermount3 >/dev/null || ! ldconfig -p | grep 'libfuse\.so\.2 ' >/dev/null; then
    if ! command -v apt-get >/dev/null; then
        printf 'Install fuse3 and the libfuse2 compatibility library with your package manager, then rerun.\n' >&2
        exit 1
    fi
    apt-get update
    if apt-cache show libfuse2t64 >/dev/null 2>&1; then
        fuse_compat=libfuse2t64
    else
        fuse_compat=libfuse2
    fi
    apt-get install -y fuse3 "$fuse_compat"
fi

if [[ ! -c /dev/fuse ]] && command -v modprobe >/dev/null; then
    modprobe fuse
fi
if [[ ! -c /dev/fuse ]]; then
    printf 'The Linux host must expose /dev/fuse.\n' >&2
    exit 1
fi

mkdir -p -- "$backing" "$share"
if [[ $(stat -f -c %t -- "$share") != 65735546 ]]; then
    if [[ -n $(find "$share" -mindepth 1 -maxdepth 1 -print -quit) ]]; then
        printf 'Choose an empty mount point. Existing entries were found in %s.\n' "$share" >&2
        exit 1
    fi
    if ! mountpoint -q -- "$share"; then
        mount --bind "$share" "$share"
    fi
    mount --make-rshared "$share"
fi

printf 'Backing directory: %s\nShared mount point: %s\n' "$backing" "$share"
fstab_path=${share//\\/\\134}
fstab_path=${fstab_path// /\\040}
fstab_path=${fstab_path//$'\t'/\\011}
printf 'Optional /etc/fstab entry for reboot persistence:\n%s %s none bind,rshared 0 0\n' "$fstab_path" "$fstab_path"
