#!/usr/bin/env bash
# Checks that the class diagrams in uml/ match the code:
#   1. every top-level type of a module appears in the module's diagram,
#   2. every type in a diagram exists in the code (unless marked <<external>>, e.g. JDK types),
#   3. every operation in a diagram exists as a method in the code.
# Exit code 0 if everything matches.
#
# Usage: scripts/check-uml.sh
set -u
source "$(dirname "$0")/common.sh"

UML_DIR="$ROOT/uml"
SOURCES="$(ls -d "$ROOT"/*/src/main/java)"
problems=0

report() {
    echo "  $*"
    problems=$((problems + 1))
}

# Types declared in a diagram: "class X", "interface X", "enum X", "abstract class X".
declared_types() {
    grep -E '^[[:space:]]*(abstract[[:space:]]+)?(class|interface|enum)[[:space:]]+[A-Za-z_]' "$1" \
        | sed -E 's/^[[:space:]]*(abstract[[:space:]]+)?(class|interface|enum)[[:space:]]+([A-Za-z_][A-Za-z0-9_]*).*/\3/' \
        | sort -u
}

# Operations in a diagram: the first "name(" of every member line starting with + - ~ #.
declared_operations() {
    grep -E '^[[:space:]]*[-+~#]' "$1" | grep -oE '[a-z][A-Za-z0-9_]*\(' | sed 's/($//' | sort -u
}

is_external() {
    grep -Eq "(class|interface|enum)[[:space:]]+$1([^A-Za-z0-9_].*)?<<external>>" "$2"
}

# shellcheck disable=SC2086
type_exists() {
    grep -rqE "(class|interface|enum|record)[[:space:]]+$1([^A-Za-z0-9_]|$)" $SOURCES
}

# shellcheck disable=SC2086
method_exists() {
    grep -rqE "[^A-Za-z0-9_.]$1\(" $SOURCES
}

# check_module_types <module> <diagram...>: every top-level type of the module is in one of the diagrams.
check_module_types() {
    local module="$1" declared="" diagram file name
    shift
    for diagram in "$@"; do
        declared="$declared
$(declared_types "$UML_DIR/$diagram")"
    done
    for file in $(find "$ROOT/$module/src/main/java" -name '*.java' ! -name package-info.java | sort); do
        name="$(basename "$file" .java)"
        if ! echo "$declared" | grep -qx "$name"; then
            report "$module: $name is missing in uml/$(echo "$@" | sed 's| | or uml/|g')"
        fi
    done
}

check_diagram() {
    local diagram="$UML_DIR/$1" name operation
    for name in $(declared_types "$diagram"); do
        if is_external "$name" "$diagram"; then
            continue
        fi
        type_exists "$name" || report "uml/$1: type $name does not exist in the code"
    done
    for operation in $(declared_operations "$diagram"); do
        method_exists "$operation" || report "uml/$1: operation $operation() does not exist in the code"
    done
}

echo "Checking uml/ against the code ..."
check_module_types common common.puml
check_module_types cf cf.puml
check_module_types ms_iot ms_iot.puml
check_module_types ms_fog ms_fog.puml
check_module_types ml_api ms_ml.puml
check_module_types ms_ml ms_ml.puml ms_ml_results.puml
check_module_types clnt_ml clnt_ml.puml
check_module_types web_ml web_ml.puml
for diagram in common.puml cf.puml ms_iot.puml ms_fog.puml ms_ml.puml ms_ml_results.puml clnt_ml.puml web_ml.puml; do
    check_diagram "$diagram"
done

if [ "$problems" = "0" ]; then
    echo "UML matches the code."
    exit 0
fi
echo "$problems problem(s) found."
exit 1
