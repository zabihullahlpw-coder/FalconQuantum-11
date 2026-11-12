#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tests/run.sh
exec java -cp .test-build com.falcon.quantum.BacktestMain "$@"
