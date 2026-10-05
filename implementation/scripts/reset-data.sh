#!/usr/bin/env bash
# Deletes the stored ML results (anomalies, predictions, comments) in run/data/.
# ML must be stopped; it creates a new, empty database on its next start.
#
# Usage: scripts/reset-data.sh [--yes]
#   --yes   do not ask
set -u
source "$(dirname "$0")/common.sh"

yes=0
for arg in "$@"; do
    case "$arg" in
        --yes) yes=1 ;;
        *) echo "Usage: $0 [--yes]" >&2; exit 2 ;;
    esac
done

for name in $ML_INSTANCES; do
    if is_running "$name"; then
        echo "$name is running; stop it first (scripts/stop-service.sh $name)" >&2
        exit 1
    fi
done
if [ ! -d "$DATA_DIR" ] || [ -z "$(ls -A "$DATA_DIR" 2>/dev/null)" ]; then
    echo "Nothing to delete: ${DATA_DIR#$ROOT/}/ is empty."
    exit 0
fi

echo "Files in ${DATA_DIR#$ROOT/}/:"
ls -lh "$DATA_DIR" | tail -n +2 | sed 's/^/  /'
if [ "$yes" != "1" ]; then
    printf "Delete all stored anomalies, predictions and comments? [y/N] "
    read -r answer
    case "$answer" in
        y|Y|yes|YES) ;;
        *) echo "Nothing deleted."; exit 0 ;;
    esac
fi
rm -f "$DATA_DIR"/*.db   # H2 files: <instance>.mv.db, <instance>.trace.db
echo "Deleted. ML starts with an empty result database next time."
