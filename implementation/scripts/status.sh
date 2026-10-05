#!/usr/bin/env bash
# Shows which instances are running, the REST and WEB URLs and, if ML runs, its status over RMI.
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

echo
echo "  ML REST API: $REST_URL ($(probe "$REST_URL/status"))"
echo "  WEB ML:      $WEB_URL ($(probe "$WEB_URL"))"
if [ -d "$DATA_DIR" ]; then
    echo "  Result DB:   ${DATA_DIR#$ROOT/}/ ($(du -sh "$DATA_DIR" | cut -f1 | tr -d ' '))"
else
    echo "  Result DB:   ${DATA_DIR#$ROOT/}/ (not created yet)"
fi

if is_running ml-1 && [ -f "$CLIENT_JAR" ]; then
    echo
    java -jar "$CLIENT_JAR" "$CLIENT_CONFIG" --status
fi
