#!/usr/bin/env sh
set -eu

: "${KOONJ_FUSE_BACKING_DIR:?Set KOONJ_FUSE_BACKING_DIR to the shared backing directory}"
: "${KOONJ_FUSE_MOUNT_POINT:?Set KOONJ_FUSE_MOUNT_POINT to an existing empty directory}"
: "${KOONJ_AGENT_CLIENT_SECRET:?Set KOONJ_AGENT_CLIENT_SECRET from the Keycloak client}"
base=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
default_jar="$base/lib/koonj-fuse.jar"
if [ ! -f "$default_jar" ]; then
    default_jar="$base/koonj-fuse/target/koonj-fuse.jar"
fi
jar=${KOONJ_FUSE_JAR:-"$default_jar"}

if [ ! -f "$jar" ]; then
    printf 'Jar not found at %s. Set KOONJ_FUSE_JAR or build it with mvn -pl koonj-fuse -am package.\n' "$jar" >&2
    exit 1
fi
exec java -Dfile.encoding=UTF-8 -jar "$jar" "$@"
