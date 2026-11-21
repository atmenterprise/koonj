#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MAVEN_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-tests)
      MAVEN_ARGS+=(-DskipTests)
      shift
      ;;
    --version)
      [[ -n "${2:-}" ]] || { echo "Missing value for --version" >&2; exit 1; }
      MAVEN_ARGS+=("-Ddeployment.version=$2")
      shift 2
      ;;
    --target)
      [[ "${2:-}" == "all" ]] || { echo "The deployment ZIP contains both targets. Use --target all." >&2; exit 1; }
      shift 2
      ;;
    --help|-h)
      echo "Usage: build.sh [--skip-tests] [--version VERSION]"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

cd "${PROJECT_ROOT}"
exec mvn -B -ntp -pl koonj-assembly -am "${MAVEN_ARGS[@]}" clean package
