#!/usr/bin/env bash
# Shows which instances are running and, if ML runs, its status over RMI.
#
# Usage: scripts/status.sh
set -u
source "$(dirname "$0")/common.sh"

for name in $ALL_INSTANCES; do
    if is_running "$name"; then
        printf "  %-11s running  (pid %s)\n" "$name" "$(pid_of "$name")"
    else
        printf "  %-11s stopped\n" "$name"
    fi
done

if is_running ml-1 && [ -f "$CLIENT_JAR" ]; then
    echo
    java -jar "$CLIENT_JAR" "$CLIENT_CONFIG" --status
fi
