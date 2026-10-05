#!/usr/bin/env bash
# Starts the minimum layout: 1 ML, 2 Fog, 6 IoT instances, each as its own background process.
#
# Usage: scripts/start-all.sh [--demo] [--build]
#   --demo    use config/demo/<instance>.json where it exists (anomalies about 3 min after start)
#   --build   build the JARs first (./mvnw package -DskipTests)
#
# Logs: run/logs/<instance>.log   PIDs: run/pids/<instance>.pid
set -u
source "$(dirname "$0")/common.sh"

demo=0
build=0
for arg in "$@"; do
    case "$arg" in
        --demo) demo=1 ;;
        --build) build=1 ;;
        *) echo "Usage: $0 [--demo] [--build]" >&2; exit 2 ;;
    esac
done

require_java
if [ "$build" = "1" ]; then
    echo "Building ..."
    (cd "$ROOT" && ./mvnw -q -B package -DskipTests) || exit 1
fi
require_jars

echo "Starting ML ..."
for name in $ML_INSTANCES; do start_instance "$name" "$demo" || exit 1; done
sleep 1
echo "Starting fog nodes ..."
for name in $FOG_INSTANCES; do start_instance "$name" "$demo" || exit 1; done
sleep 1
echo "Starting IoT devices ..."
for name in $IOT_INSTANCES; do start_instance "$name" "$demo" || exit 1; done

sleep 3
# shellcheck disable=SC2086
if ! check_started $ALL_INSTANCES; then
    echo "Some instances failed to start; stop the rest with scripts/stop-all.sh" >&2
    exit 1
fi

echo
echo "All 9 instances are running."
echo "  scripts/client.sh            poll ML and print anomalies and prediction changes (Ctrl+C to stop)"
echo "  scripts/client.sh --status   show the state of ML and its fog subscriptions"
echo "  scripts/status.sh            show which instances are running"
echo "  scripts/stop-all.sh          stop everything"
