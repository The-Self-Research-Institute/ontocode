#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARS_DIR="$SCRIPT_DIR/electron-app/resources/backend/jars"
DESKTOP_VERSION="1.0.0"
SWRL_VERSION="1.0.0"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/scripts/check-jdk-prereqs.sh"
require_jdk_prereqs   # sets JDK17_HOME / JDK21_HOME, or exits with a clear message

# desktop.jar (auth+editor+plugin) needs 21; swrl.jar needs 17 (Drools/MVEL vs newer JDKs).
# Switching JAVA_HOME per phase below means one command builds both correctly regardless
# of whatever JDK happens to be active in the calling shell.
use_jdk() {
    export JAVA_HOME="$1"
    export PATH="$JAVA_HOME/bin:$PATH"
    hash -r 2>/dev/null || true
}

KNOWN_PLATFORMS="win mac linux all"
PLATFORM=""

if [ $# -gt 0 ]; then
    for p in $KNOWN_PLATFORMS; do
        if [ "$1" = "$p" ]; then
            PLATFORM="$1"
            shift
            break
        fi
    done
fi

if [ -z "$PLATFORM" ]; then
    case "$(uname -s)" in
        Darwin*)             PLATFORM="mac" ;;
        Linux*)              PLATFORM="linux" ;;
        MINGW*|MSYS*|CYGWIN*) PLATFORM="win" ;;
        *)                   PLATFORM="linux" ;;
    esac
fi

FILTER=("$@")

run_desktop=false
run_swrl=false
run_ui=false
run_pack=false

if [ ${#FILTER[@]} -eq 0 ]; then
    run_desktop=true; run_swrl=true; run_ui=true; run_pack=true
else
    for f in "${FILTER[@]}"; do
        case "$f" in
            desktop) run_desktop=true ;;
            swrl)    run_swrl=true ;;
            jars)    run_desktop=true; run_swrl=true ;;
            ui)      run_ui=true ;;
            pack)    run_pack=true ;;
            *)       echo "WARNING: Unknown step '$f' — ignored" ;;
        esac
    done
fi

step() { echo ""; echo "── $1 ───────────────────────────────────────"; }

copy_jar() {
    local src="$1" dst="$2" label="$3"
    if [ ! -f "$src" ]; then
        echo "ERROR: JAR not found: $src"
        echo "       Run 'mvn package -DskipTests' in the relevant module first."
        exit 1
    fi
    cp "$src" "$dst"
    echo "  ✓ $label  →  $(basename $dst)"
}

fail() {
    echo ""
    echo "============================================"
    echo "   BUILD FAILED — see error above"
    echo "============================================"
    exit 1
}

trap fail ERR

echo ""
echo "============================================"
echo "   Building OntoCode Desktop"
echo "   Platform : $PLATFORM"
echo "   Steps    : $([ ${#FILTER[@]} -eq 0 ] && echo ALL || echo "${FILTER[*]}")"
echo "   Root     : $SCRIPT_DIR"
echo "============================================"
echo ""
mkdir -p "$JARS_DIR"

if $run_desktop; then
    use_jdk "$JDK21_HOME"
    step "Maven — install shared modules"
    cd "$SCRIPT_DIR"
    mvn install -pl shared/common-models,shared/common-utils -DskipTests -q
    echo "  ✓ shared modules installed"

    step "Maven — install auth, editor, plugin"
    mvn install -pl ontology-auth,ontology-editor -DskipTests -q
    echo "  ✓ auth, editor installed"

    cd "$SCRIPT_DIR/ontology-plugin-service"
    mvn install -DskipTests -q
    cd "$SCRIPT_DIR"
    echo "  ✓ plugin-service installed"

    step "Maven — build ontology-desktop (combined JAR)"
    cd "$SCRIPT_DIR/ontology-desktop"
    mvn package -DskipTests -q
    cd "$SCRIPT_DIR"

    copy_jar \
        "$SCRIPT_DIR/ontology-desktop/target/ontology-desktop-${DESKTOP_VERSION}.jar" \
        "$JARS_DIR/desktop.jar" \
        "desktop (auth + editor + plugin)"
fi

if $run_swrl; then
    use_jdk "$JDK17_HOME"
    step "Maven — ontology-swrl (separate JVM, owlapi 4.x)"
    cd "$SCRIPT_DIR"
    mvn package -pl ontology-swrl -DskipTests -q

    copy_jar \
        "$SCRIPT_DIR/ontology-swrl/target/ontology-swrl-${SWRL_VERSION}.jar" \
        "$JARS_DIR/swrl.jar" \
        "swrl"
fi

if $run_ui && ! $run_pack; then
    # Skipped when run_pack is also set: the npm dist:* scripts used by the pack step
    # below already run build:electron themselves — doing it here too would just build
    # the same UI twice.
    step "Building React UI"
    cd "$SCRIPT_DIR/ontology-vscode-extension/webview-src"
    npm run build:electron
    echo "  ✓ React UI built"
    cd "$SCRIPT_DIR"
fi

if $run_pack; then
    step "Packaging Electron app — $PLATFORM"
    cd "$SCRIPT_DIR/electron-app"
    # Route through the npm dist scripts, not `electron-builder` directly: that skips
    # prepare-resources.js (JRE download/embed, fuseki-server.jar + mongod copy) entirely,
    # which only "worked" before because those resources happened to already exist on disk
    # from an earlier manual run. A truly first-time run would silently ship a broken
    # installer missing its bundled runtime.
    # DIST_TARGET lets callers point at the dev update URL, e.g.:
    #   DIST_TARGET_SUFFIX=":dev" bash build-desktop.sh win
    case "$PLATFORM" in
        win)   npm run "dist:win${DIST_TARGET_SUFFIX:-}" ;;
        mac)   npm run "dist:mac${DIST_TARGET_SUFFIX:-}" ;;
        linux) npm run "dist:linux${DIST_TARGET_SUFFIX:-}" ;;
        all)   npm run "dist:win${DIST_TARGET_SUFFIX:-}" && npm run "dist:mac${DIST_TARGET_SUFFIX:-}" && npm run "dist:linux${DIST_TARGET_SUFFIX:-}" ;;
    esac
    echo "  ✓ Electron app packaged"
    cd "$SCRIPT_DIR"
fi

trap - ERR

echo ""
echo "============================================"
echo "   SUCCESS!"
if $run_pack; then
    echo ""
    echo "Packaged installer is in:"
    echo "  electron-app/dist-electron/"
fi
echo "============================================"
echo ""
