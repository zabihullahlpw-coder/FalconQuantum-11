#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v java >/dev/null || { echo "Install JDK 17." >&2; exit 1; }
command -v gradle >/dev/null || { echo "Install Gradle 8.7 (or generate its official wrapper)." >&2; exit 1; }
if [[ -z "${ANDROID_HOME:-}" && ! -f local.properties ]]; then
  echo "Set ANDROID_HOME or sdk.dir in local.properties. Install platform 34 and Build Tools 34.0.0." >&2
  exit 1
fi
gradle --no-daemon assembleDebug
