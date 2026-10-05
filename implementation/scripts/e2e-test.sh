#!/usr/bin/env bash
# IT-CLNT / end-to-end test of the full minimum layout (6 IoT, 2 Fog, 1 ML) with CLNT ML.
# Uses the demo configs, so the first injected anomalies arrive about 3 minutes after start.
# Takes about 5 minutes. Exit code 0 if every step passed.
#
# Usage: scripts/e2e-test.sh [--build]
#
#   1  Start everything, wait 30 s: both fogs aggregate data of 3 devices, ML has 2 ACTIVE fogs
#   2  clnt_ml --once: exit 0, prediction lines for both data types
#   3  clnt_ml --once repeatedly (same cursor file): the injected temperature drop is reported
#      as temp-sudden-drop, and no event line is ever printed twice
#   4  ML is killed (SIGKILL) while a client polls: the client warns "unreachable" and keeps running
#   5  ML is restarted: the client reports the restart, resets its cursor and prints new events
#   Post: stop-all leaves no process behind
set -u
source "$(dirname "$0")/common.sh"

E2E_DIR="$RUN_DIR/e2e"
CURSORS="$E2E_DIR/cursors.json"
SEEN="$E2E_DIR/seen-events.txt"
CLIENT_LOG="$E2E_DIR/polling-client.log"
client_pid=""
failures=0

pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; failures=$((failures + 1)); }
step() { echo; echo "Step $*"; }

cleanup() {
    if [ -n "$client_pid" ] && kill -0 "$client_pid" 2>/dev/null; then
        kill -TERM "$client_pid" 2>/dev/null
        wait "$client_pid" 2>/dev/null
    fi
    "$SCRIPT_DIR/stop-all.sh" > /dev/null
}
trap cleanup EXIT

# wait_for <seconds> <command...>: retries the command every second until it succeeds.
wait_for() {
    local seconds="$1" i=0
    shift
    while [ "$i" -lt "$seconds" ]; do
        if "$@" > /dev/null 2>&1; then
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    return 1
}

client() {
    java -jar "$CLIENT_JAR" "$CLIENT_CONFIG" "$@"
}

# Event lines of an output (anomalies and prediction changes).
event_lines() {
    grep -E "  (ANOMALY|PREDICTION)  " || true
}

# Appends new event lines to $SEEN; fails if one of them was printed before.
record_events() {
    local events="$1" line duplicates=0
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        if grep -Fxq -- "$line" "$SEEN"; then
            echo "    printed twice: $line"
            duplicates=1
        fi
        echo "$line" >> "$SEEN"
    done <<< "$events"
    return "$duplicates"
}

for arg in "$@"; do
    case "$arg" in
        --build) (cd "$ROOT" && ./mvnw -q -B package -DskipTests) || exit 1 ;;
        *) echo "Usage: $0 [--build]" >&2; exit 2 ;;
    esac
done
require_java
require_jars
# shellcheck disable=SC2086
for name in $ALL_INSTANCES; do
    if is_running "$name"; then
        echo "$name is already running; stop everything first (scripts/stop-all.sh)" >&2
        trap - EXIT
        exit 1
    fi
done
mkdir -p "$E2E_DIR"
rm -f "$CURSORS" "$SEEN" "$CLIENT_LOG"
touch "$SEEN"
started_at=$(date +%s)

step "1: start everything, wait 30 s"
if "$SCRIPT_DIR/start-all.sh" --demo > "$E2E_DIR/start-all.log" 2>&1; then
    pass "all 9 instances started"
else
    fail "start-all failed:"; sed 's/^/    /' "$E2E_DIR/start-all.log"
    exit 1
fi
sleep 30
for fog in fog-temp fog-co2; do
    if grep -q "readings from 3 devices" "$LOG_DIR/$fog.log"; then
        pass "$fog aggregates readings of 3 devices"
    else
        fail "$fog has not aggregated readings of 3 devices"
    fi
done
status="$(client --status)"
if echo "$status" | grep -q "fog-temp: ACTIVE" && echo "$status" | grep -q "fog-co2: ACTIVE"; then
    pass "ML is subscribed at both fogs (status: ACTIVE)"
else
    fail "ML is not ACTIVE at both fogs:"; echo "$status" | sed 's/^/    /'
fi

step "2: clnt_ml --once"
output="$(client --once --cursor-file "$CURSORS")"
code=$?
events="$(echo "$output" | event_lines)"
if [ "$code" = "0" ]; then pass "exit code 0"; else fail "exit code $code"; fi
if echo "$events" | grep -q "PREDICTION  co2" && echo "$events" | grep -q "PREDICTION  temperature"; then
    pass "prediction lines for co2 and temperature"
else
    fail "missing prediction lines:"; echo "$output" | sed 's/^/    /'
fi
record_events "$events" || fail "an event was printed twice"

step "3: clnt_ml --once until the injected drop is reported (up to 5 min)"
anomaly_found=0
duplicates=0
deadline=$(( $(date +%s) + 300 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    sleep 10
    events="$(client --once --cursor-file "$CURSORS" | event_lines)"
    record_events "$events" || duplicates=1
    if echo "$events" | grep -q "ANOMALY     temp-sudden-drop"; then
        anomaly_found=1
        echo "$events" | grep "ANOMALY" | sed 's/^/    /'
        break
    fi
done
if [ "$anomaly_found" = "1" ]; then
    pass "temp-sudden-drop reported $(( $(date +%s) - started_at )) s after start"
else
    fail "no temp-sudden-drop anomaly within 5 minutes"
fi
if [ "$duplicates" = "0" ]; then pass "no event line printed twice"; else fail "event lines were printed twice"; fi

step "4: kill ML while a client polls"
java -jar "$CLIENT_JAR" "$CLIENT_CONFIG" --interval 2000 > "$CLIENT_LOG" 2>&1 &
client_pid=$!
wait_for 20 grep -q "connected to" "$CLIENT_LOG" || fail "polling client did not connect"
kill -KILL "$(pid_of ml-1)"
rm -f "$PID_DIR/ml-1.pid"
if wait_for 20 grep -q "WARN        unreachable" "$CLIENT_LOG"; then
    pass "client warns: $(grep -m1 "WARN        unreachable" "$CLIENT_LOG" | sed 's/.*WARN *//')"
else
    fail "client did not report the unreachable ML"
fi
sleep 5
if kill -0 "$client_pid" 2>/dev/null; then pass "client keeps running"; else fail "client exited"; fi

step "5: restart ML"
lines_before=$(wc -l < "$CLIENT_LOG")
"$SCRIPT_DIR/start-service.sh" ml-1 --demo > /dev/null || fail "ML did not restart"
if wait_for 60 grep -q "server restarted (new epoch), cursor reset" "$CLIENT_LOG"; then
    pass "client reports the restart and resets its cursor"
else
    fail "client did not report the restart"
fi
new_events() { tail -n +"$((lines_before + 1))" "$CLIENT_LOG" | grep -qE "  (ANOMALY|PREDICTION)  "; }
if wait_for 60 new_events; then
    pass "client prints events of the new ML instance"
else
    fail "no events of the new ML instance"
fi

step "post: stop everything"
kill -TERM "$client_pid" 2>/dev/null
wait "$client_pid" 2>/dev/null
client_pid=""
"$SCRIPT_DIR/stop-all.sh" > /dev/null
leftovers=""
for name in $ALL_INSTANCES; do
    if is_running "$name"; then leftovers="$leftovers $name"; fi
done
if [ -z "$leftovers" ]; then pass "no instance left running"; else fail "still running:$leftovers"; fi
trap - EXIT

echo
echo "Logs: ${LOG_DIR#$ROOT/}/ and ${E2E_DIR#$ROOT/}/   Duration: $(( $(date +%s) - started_at )) s"
if [ "$failures" = "0" ]; then
    echo "E2E PASSED"
    exit 0
fi
echo "E2E FAILED ($failures failed checks)"
exit 1
