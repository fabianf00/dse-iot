#!/usr/bin/env bash
# Runs CLNT ML with config/clnt-ml.json (or $CLNT_CONFIG). Further arguments are passed on.
#
# Usage: scripts/client.sh [--interval <ms>] [--once | --status] [--cursor-file <path>]
set -u
source "$(dirname "$0")/common.sh"

require_java
if [ ! -f "$CLIENT_JAR" ]; then
    echo "missing $CLIENT_JAR; build first: (cd $ROOT && ./mvnw package -DskipTests)" >&2
    exit 1
fi
exec java -jar "$CLIENT_JAR" "$CLIENT_CONFIG" "$@"
