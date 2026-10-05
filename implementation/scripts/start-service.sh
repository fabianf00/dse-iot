#!/usr/bin/env bash
# Starts one instance, e.g. after it was stopped or crashed.
#
# Usage: scripts/start-service.sh <instance> [--demo]
#   instances: ml-1, fog-temp, fog-co2, iot-temp-1..3, iot-co2-1..3, web-ml
set -u
source "$(dirname "$0")/common.sh"

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    echo "Usage: $0 <instance> [--demo]" >&2
    exit 2
fi
demo=0
if [ "${2:-}" = "--demo" ]; then
    demo=1
fi

require_java
require_jars
start_instance "$1" "$demo" || exit 1
sleep 2
check_started "$1"
