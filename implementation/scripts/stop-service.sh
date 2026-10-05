#!/usr/bin/env bash
# Stops one instance gracefully (SIGTERM; SIGKILL after 10 s).
#
# Usage: scripts/stop-service.sh <instance>
set -u
source "$(dirname "$0")/common.sh"

if [ $# -ne 1 ]; then
    echo "Usage: $0 <instance>" >&2
    exit 2
fi
if ! is_running "$1"; then
    echo "  $1 is not running"
    rm -f "$PID_DIR/$1.pid"
    exit 0
fi
stop_instance "$1"
