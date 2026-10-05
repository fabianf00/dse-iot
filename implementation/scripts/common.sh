# Shared helpers of the start/stop scripts. Sourced, not executed.
# Compatible with the bash 3.2 that ships with macOS.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG_DIR="$ROOT/config"
DEMO_CONFIG_DIR="$CONFIG_DIR/demo"
RUN_DIR="$ROOT/run"
PID_DIR="$RUN_DIR/pids"
LOG_DIR="$RUN_DIR/logs"

# Start order: ML first, then the fogs, then the devices, then the web UI. The order is not
# needed for correctness (everything retries), it only avoids warnings while the system comes up.
ML_INSTANCES="ml-1"
FOG_INSTANCES="fog-temp fog-co2"
IOT_INSTANCES="iot-temp-1 iot-temp-2 iot-temp-3 iot-co2-1 iot-co2-2 iot-co2-3"
WEB_INSTANCES="web-ml"
ALL_INSTANCES="$ML_INSTANCES $FOG_INSTANCES $IOT_INSTANCES $WEB_INSTANCES"

# URLs for humans and the e2e test; must match config/ml-1.json (rest) and config/web-ml.json.
REST_URL="http://127.0.0.1:50303/api/v1"
WEB_URL="http://127.0.0.1:50400/"
DATA_DIR="$RUN_DIR/data"

CLIENT_JAR="$ROOT/clnt_ml/target/clnt_ml.jar"
CLIENT_CONFIG="${CLNT_CONFIG:-$CONFIG_DIR/clnt-ml.json}"

# JAR of an instance, derived from its name.
jar_of() {
    case "$1" in
        ml-*) echo "$ROOT/ms_ml/target/ms_ml.jar" ;;
        fog-*) echo "$ROOT/ms_fog/target/ms_fog.jar" ;;
        iot-*) echo "$ROOT/ms_iot/target/ms_iot.jar" ;;
        web-*) echo "$ROOT/web_ml/target/web_ml.jar" ;;
        *) return 1 ;;
    esac
}

# Config of an instance; in demo mode config/demo/<name>.json wins if it exists.
config_of() {
    local name="$1" demo="$2"
    if [ "$demo" = "1" ] && [ -f "$DEMO_CONFIG_DIR/$name.json" ]; then
        echo "$DEMO_CONFIG_DIR/$name.json"
    else
        echo "$CONFIG_DIR/$name.json"
    fi
}

pid_of() {
    cat "$PID_DIR/$1.pid" 2>/dev/null
}

is_running() {
    local pid
    pid="$(pid_of "$1")"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

require_java() {
    if ! command -v java >/dev/null 2>&1; then
        echo "java not found; install Java 17 or newer" >&2
        exit 1
    fi
}

require_jars() {
    local jar missing=0
    for jar in "$ROOT/ms_ml/target/ms_ml.jar" "$ROOT/ms_fog/target/ms_fog.jar" "$ROOT/ms_iot/target/ms_iot.jar" \
            "$ROOT/web_ml/target/web_ml.jar" "$CLIENT_JAR"; do
        if [ ! -f "$jar" ]; then
            echo "missing $jar" >&2
            missing=1
        fi
    done
    if [ "$missing" = "1" ]; then
        echo "build first: (cd $ROOT && ./mvnw package -DskipTests), or use --build" >&2
        exit 1
    fi
}

# start_instance <name> <demo 0|1>
start_instance() {
    local name="$1" demo="$2" jar config
    if is_running "$name"; then
        echo "  $name already running (pid $(pid_of "$name"))"
        return 0
    fi
    jar="$(jar_of "$name")" || { echo "  unknown instance $name" >&2; return 1; }
    config="$(config_of "$name" "$demo")"
    if [ ! -f "$config" ]; then
        echo "  no config for $name: $config" >&2
        return 1
    fi
    mkdir -p "$PID_DIR" "$LOG_DIR" "$DATA_DIR"
    # Started from implementation/, so relative paths in configs (e.g. the ML database under
    # run/data) do not depend on the directory the script was called from.
    (cd "$ROOT" && exec nohup java -jar "$jar" "$config" > "$LOG_DIR/$name.log" 2>&1) &
    echo $! > "$PID_DIR/$name.pid"
    echo "  started $name (pid $!, config ${config#$ROOT/}, log ${LOG_DIR#$ROOT/}/$name.log)"
}

# stop_instance <name>: SIGTERM (the service says goodbye to its peers), SIGKILL after 10 s.
stop_instance() {
    local name="$1" pid waited=0
    pid="$(pid_of "$name")"
    if [ -z "$pid" ]; then
        return 0
    fi
    if kill -0 "$pid" 2>/dev/null; then
        kill -TERM "$pid" 2>/dev/null
        while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 100 ]; do
            sleep 0.1
            waited=$((waited + 1))
        done
        if kill -0 "$pid" 2>/dev/null; then
            echo "  $name did not stop within 10 s, killing it"
            kill -KILL "$pid" 2>/dev/null
        fi
        echo "  stopped $name (pid $pid)"
    fi
    rm -f "$PID_DIR/$name.pid"
}

# probe <url>: "reachable" if an HTTP GET succeeds within 2 s, otherwise "not reachable".
probe() {
    if ! command -v curl >/dev/null 2>&1; then
        echo "curl not installed"
    elif curl -sf -o /dev/null -m 2 "$1"; then
        echo "reachable"
    else
        echo "not reachable"
    fi
}

# Fails if an instance exited shortly after its start (e.g. invalid config, port in use).
check_started() {
    local name failed=0
    for name in "$@"; do
        if ! is_running "$name"; then
            echo "  $name exited right after its start; last lines of its log:" >&2
            tail -n 5 "$LOG_DIR/$name.log" 2>/dev/null | sed 's/^/    /' >&2
            failed=1
        fi
    done
    return "$failed"
}
