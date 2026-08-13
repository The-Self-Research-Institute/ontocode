#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARS_DIR="$SCRIPT_DIR/electron-app/resources/backend/jars"
DESKTOP_VERSION="1.0.0"
SWRL_VERSION="1.0.0"

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
    step "Maven — ontology-swrl (separate JVM, owlapi 4.x)"
    cd "$SCRIPT_DIR"
    mvn package -pl ontology-swrl -DskipTests -q

    copy_jar \
        "$SCRIPT_DIR/ontology-swrl/target/ontology-swrl-${SWRL_VERSION}.jar" \
        "$JARS_DIR/swrl.jar" \
        "swrl"
fi

if $run_ui; then
    step "Building React UI"
    cd "$SCRIPT_DIR/ontology-vscode-extension/webview-src"
    npm run build:electron
    echo "  ✓ React UI built"
    cd "$SCRIPT_DIR"
fi

if $run_pack; then
    step "Packaging Electron app — $PLATFORM"
    cd "$SCRIPT_DIR/electron-app"
    case "$PLATFORM" in
        win)   electron-builder --win ;;
        mac)   electron-builder --mac ;;
        linux) electron-builder --linux ;;
        all)   electron-builder --win --mac --linux ;;
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
    echo "  electron-app/dist/"
fi
echo "============================================"
echo ""
