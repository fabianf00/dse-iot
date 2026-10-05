#!/usr/bin/env bash
# Renders uml/*.puml to PNG next to the sources. Needs PlantUML: either a "plantuml" command,
# or the path of a PlantUML jar in $PLANTUML_JAR (https://plantuml.com/download). Class
# diagrams also need Graphviz ("dot") unless the PlantUML version brings its own layout.
#
# Usage: scripts/render-uml.sh
set -u
source "$(dirname "$0")/common.sh"

# PlantUML crops images larger than 4096 px by default; the bigger diagrams need more.
export PLANTUML_LIMIT_SIZE="${PLANTUML_LIMIT_SIZE:-8192}"
if [ -n "${PLANTUML_JAR:-}" ]; then
    java -DPLANTUML_LIMIT_SIZE="$PLANTUML_LIMIT_SIZE" -jar "$PLANTUML_JAR" -failfast2 -tpng "$ROOT"/uml/*.puml
elif command -v plantuml >/dev/null 2>&1; then
    plantuml -failfast2 -tpng "$ROOT"/uml/*.puml
else
    echo "PlantUML not found: install it, or set PLANTUML_JAR to the path of plantuml.jar" >&2
    exit 1
fi
echo "Rendered: $(cd "$ROOT/uml" && ls *.png | tr '\n' ' ')"
