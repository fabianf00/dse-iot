#!/usr/bin/env bash
# Stops all instances started by start-all.sh or start-service.sh (web UI and devices first, ML last).
#
# Usage: scripts/stop-all.sh
set -u
source "$(dirname "$0")/common.sh"

echo "Stopping ..."
for name in $WEB_INSTANCES $IOT_INSTANCES $FOG_INSTANCES $ML_INSTANCES; do
    stop_instance "$name"
done
echo "All instances stopped."
