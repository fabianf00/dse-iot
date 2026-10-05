#!/usr/bin/env bash
# IT-CLNT / end-to-end test of the full minimum layout (6 IoT, 2 Fog, 1 ML, WEB ML) with CLNT ML,
# the ML REST API and WEB ML. Uses the demo configs, so the first injected anomalies arrive
# about 3 minutes after start. Takes about 5 minutes. Exit code 0 if every step passed.
# Needs curl. Results of earlier runs in run/data/ do not matter (checks only look at this run).
#
# Usage: scripts/e2e-test.sh [--build]
#
#   1  Start everything, wait 30 s: both fogs aggregate data of 3 devices, ML has 2 ACTIVE fogs
#   2  clnt_ml --once: exit 0, prediction lines for both data types
#   3  clnt_ml --once repeatedly (same cursor file): the injected temperature drop is reported
#      as temp-sudden-drop, and no event line is ever printed twice
#   4  REST API: the anomaly is stored, comment round trip (add, edit, delete), live endpoints,
#      problem details, CORS for the WEB ML origin; WEB ML serves the UI and points it to the API
#   5  ML is killed (SIGKILL) while a client polls: the client warns "unreachable" and keeps running
#   6  ML is restarted: the client reports the restart, resets its cursor and prints new events;
#      the REST API still serves the stored anomaly
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

# http <method> <url> [json body]: sets HTTP_STATUS, HTTP_BODY and HTTP_HEADERS.
http() {
    local method="$1" url="$2" body="${3:-}"
    local args=(-s -m 10 -X "$method" -D "$E2E_DIR/headers.txt" -o "$E2E_DIR/body.txt" -w "%{http_code}")
    if [ -n "$body" ]; then
        args+=(-H "Content-Type: application/json" --data "$body")
    fi
    : > "$E2E_DIR/headers.txt"
    : > "$E2E_DIR/body.txt"
    HTTP_STATUS="$(curl "${args[@]}" "$url")" || HTTP_STATUS="000"
    HTTP_BODY="$(cat "$E2E_DIR/body.txt")"
    HTTP_HEADERS="$(tr -d '\r' < "$E2E_DIR/headers.txt")"
}

# json_number <field> <json>: first number value of the field (enough for these flat checks).
json_number() {
    echo "$2" | grep -o "\"$1\":[0-9]*" | head -n 1 | cut -d: -f2
}

# True if this run's temp-sudden-drop anomaly is stored; sets ANOMALY_ID.
anomaly_stored() {
    local total
    http GET "$REST_URL/anomalies?chainId=temp-sudden-drop&from=$started_iso"
    total="$(json_number total "$HTTP_BODY")"
    [ "$HTTP_STATUS" = "200" ] && [ "${total:-0}" -ge 1 ] || return 1
    ANOMALY_ID="$(json_number id "$HTTP_BODY")"
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
if ! command -v curl >/dev/null 2>&1; then
    echo "curl not found; it is needed for the REST and WEB checks" >&2
    trap - EXIT
    exit 1
fi
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
started_iso="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
ANOMALY_ID=""

step "1: start everything, wait 30 s"
if "$SCRIPT_DIR/start-all.sh" --demo > "$E2E_DIR/start-all.log" 2>&1; then
    pass "all 10 instances started"
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

step "4: REST API and WEB ML"
if wait_for 15 anomaly_stored; then
    pass "the anomaly is stored (GET /anomalies?chainId=temp-sudden-drop: id $ANOMALY_ID)"
else
    fail "the anomaly is not stored (status $HTTP_STATUS): $HTTP_BODY"
fi
if [ -n "$ANOMALY_ID" ]; then
    http POST "$REST_URL/anomalies/$ANOMALY_ID/comments" '{"text":"e2e check","author":"e2e-test"}'
    comment_id="$(echo "$HTTP_HEADERS" | grep -i "^location: /api/v1/comments/" | sed 's|.*/comments/||')"
    if [ "$HTTP_STATUS" = "201" ] && [ -n "$comment_id" ]; then
        pass "POST comment: 201, Location /api/v1/comments/$comment_id"
    else
        fail "POST comment: status $HTTP_STATUS, $HTTP_BODY"
    fi
    http GET "$REST_URL/anomalies/$ANOMALY_ID"
    if echo "$HTTP_BODY" | grep -q '"text":"e2e check"'; then
        pass "GET anomaly shows the comment"
    else
        fail "comment missing: $HTTP_BODY"
    fi
    http PUT "$REST_URL/comments/$comment_id" '{"text":"e2e check, edited"}'
    if [ "$HTTP_STATUS" = "200" ] && echo "$HTTP_BODY" | grep -q '"text":"e2e check, edited"'; then
        pass "PUT comment: 200 with the new text"
    else
        fail "PUT comment: status $HTTP_STATUS, $HTTP_BODY"
    fi
    http DELETE "$REST_URL/comments/$comment_id"
    delete_status="$HTTP_STATUS"
    http GET "$REST_URL/anomalies/$ANOMALY_ID/comments"
    if [ "$delete_status" = "204" ] && [ "$HTTP_BODY" = "[]" ]; then
        pass "DELETE comment: 204, no comments left"
    else
        fail "DELETE comment: status $delete_status, comments $HTTP_BODY"
    fi
fi
http GET "$REST_URL/predictions/current"
if [ "$HTTP_STATUS" = "200" ] && echo "$HTTP_BODY" | grep -q '"dataType":"co2"' \
        && echo "$HTTP_BODY" | grep -q '"dataType":"temperature"'; then
    pass "GET /predictions/current: co2 and temperature"
else
    fail "GET /predictions/current: status $HTTP_STATUS, $HTTP_BODY"
fi
http GET "$REST_URL/anomalies?limit=0"
if [ "$HTTP_STATUS" = "400" ] && echo "$HTTP_HEADERS" | grep -qi "^content-type: application/problem+json"; then
    pass "invalid request: 400 with problem details"
else
    fail "invalid request: status $HTTP_STATUS, $HTTP_BODY"
fi
web_origin="${WEB_URL%/}"
curl -s -m 10 -o /dev/null -D "$E2E_DIR/headers.txt" -X OPTIONS -H "Origin: $web_origin" \
    -H "Access-Control-Request-Method: DELETE" "$REST_URL/anomalies"
if tr -d '\r' < "$E2E_DIR/headers.txt" | grep -qi "^access-control-allow-origin: $web_origin$"; then
    pass "CORS: $web_origin may call the REST API"
else
    fail "CORS preflight for $web_origin is not allowed"
fi
http GET "$WEB_URL"
if [ "$HTTP_STATUS" = "200" ] && echo "$HTTP_BODY" | grep -q 'src="app.js"'; then
    pass "WEB ML serves the UI"
else
    fail "WEB ML /: status $HTTP_STATUS"
fi
http GET "${WEB_URL}config.json"
if [ "$HTTP_STATUS" = "200" ] && echo "$HTTP_BODY" | grep -qF "\"restUrl\":\"$REST_URL\""; then
    pass "WEB ML /config.json points to $REST_URL"
else
    fail "WEB ML /config.json: status $HTTP_STATUS, $HTTP_BODY"
fi

step "5: kill ML while a client polls"
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

step "6: restart ML"
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
if [ -n "$ANOMALY_ID" ] && wait_for 30 anomaly_stored; then
    pass "the REST API still serves the stored anomaly after the restart"
else
    fail "the stored anomaly is not served after the restart"
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
