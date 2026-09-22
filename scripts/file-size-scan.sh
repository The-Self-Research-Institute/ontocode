#!/usr/bin/env bash
# Scans the repo for section 3 file-size review-threshold violations.
# Counts nonblank physical lines (a line counts if it has any non-whitespace
# character), matching ESLint max-lines with skipBlankLines and the intent of
# the section 3 table. Prints one "path|category|lines|threshold" row per
# violation, sorted by path. Read-only: makes no changes to the repo.
set -euo pipefail
cd "$(dirname "$0")/.."

nonblank_lines() {
    awk 'NF{c++} END{print c+0}' "$1"
}

classify_and_check() {
    local file="$1"
    local category="$2"
    local threshold="$3"
    local lines
    lines=$(nonblank_lines "$file")
    if [ "$lines" -gt "$threshold" ]; then
        printf '%s|%s|%s|%s\n' "$file" "$category" "$lines" "$threshold"
    fi
}

EXCLUDE_DIRS='node_modules|/dist/|/build/|/out/|/target/|\.git/|/coverage/|\.turbo|OntoCode-win32-x64|\.claude/worktrees|/\.tmp/|/tmp/'

# React component files (webview-src, plugins' webview code): review threshold 300
while IFS= read -r -d '' f; do
    base=$(basename "$f")
    case "$base" in
        *.test.tsx|*.spec.tsx) continue ;;
    esac
    if grep -qE '/(hooks?|custom-hook)/' <<<"$f"; then
        continue
    fi
    classify_and_check "$f" "react-component" 300
done < <(find . -type f -name '*.tsx' \
    \( -path '*/webview-src/*' -o -path '*/plugins/*/src/*' \) \
    | grep -Ev "$EXCLUDE_DIRS" | tr '\n' '\0')

# Custom hook files: review threshold 150
while IFS= read -r -d '' f; do
    classify_and_check "$f" "custom-hook" 150
done < <(find . -type f \( -name '*.ts' -o -name '*.tsx' \) \
    \( -path '*/hooks/*' -o -path '*/custom-hook/*' \) \
    -not -name '*.test.*' -not -name '*.spec.*' \
    | grep -Ev "$EXCLUDE_DIRS" | tr '\n' '\0')

# Java controller files: review threshold 250
while IFS= read -r -d '' f; do
    classify_and_check "$f" "java-controller" 250
done < <(find . -type f -name '*Controller.java' -path '*/controller/*' \
    | grep -Ev "$EXCLUDE_DIRS" | tr '\n' '\0')

# Java service files: review threshold 400
while IFS= read -r -d '' f; do
    classify_and_check "$f" "java-service" 400
done < <(find . -type f -name '*Service.java' -path '*/service/*' \
    | grep -Ev "$EXCLUDE_DIRS" | tr '\n' '\0')

# Utility / API client files (TS utils+services outside hooks/components, Java util/*.java): review threshold 300
while IFS= read -r -d '' f; do
    classify_and_check "$f" "utility-api-client" 300
done < <(find . -type f \( -name '*.ts' -o -name '*.tsx' \) \
    \( -path '*/utils/*' -o -path '*/services/*' -o -path '*/config/*' \) \
    -not -name '*.test.*' -not -name '*.spec.*' -not -path '*/hooks/*' \
    | grep -Ev "$EXCLUDE_DIRS" | tr '\n' '\0')

while IFS= read -r -d '' f; do
    classify_and_check "$f" "utility-api-client" 300
done < <(find . -type f -name '*.java' -path '*/util/*' \
    | grep -Ev "$EXCLUDE_DIRS" | tr '\n' '\0')

# Test files: review threshold 600
while IFS= read -r -d '' f; do
    classify_and_check "$f" "test-file" 600
done < <(find . -type f \( -name '*.test.ts' -o -name '*.test.tsx' -o -name '*Test.java' -o -name '*Tests.java' -o -name '*.spec.ts' -o -name '*.spec.tsx' \) \
    | grep -Ev "$EXCLUDE_DIRS" | tr '\n' '\0')
