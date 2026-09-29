#!/usr/bin/env bash
# section 9/14 patch-coverage wrapper: resolves the git merge-base
# against a base ref, runs each affected sub-project's own coverage command,
# then computes patch coverage restricted to changed executable lines via
# scripts/patch_coverage.py. Missing or unreadable reports fail verification
# rather than being counted as passing, per section 9.
#
# Usage: scripts/coverage-wrapper.sh [base-ref] [head-ref]
#   base-ref defaults to origin/develop, head-ref defaults to HEAD.
set -uo pipefail

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

base_ref="${1:-origin/develop}"
head_ref="${2:-HEAD}"

merge_base=$(git merge-base "$base_ref" "$head_ref" 2>/dev/null)
if [ -z "$merge_base" ]; then
    echo "coverage-wrapper: could not resolve a merge-base between $base_ref and $head_ref." >&2
    exit 1
fi

changed_files=$(git diff --name-only "$merge_base" "$head_ref")
echo "coverage-wrapper: base=$merge_base (from $base_ref) head=$head_ref" >&2

overall_status=0

webview_changed=$(echo "$changed_files" | grep -E '^ontology-vscode-extension/webview-src/' | grep -Ev '\.(test|spec)\.tsx?$' || true)
if [ -n "$webview_changed" ]; then
    echo "" >&2
    echo "=== webview-src (ontology-vscode-extension/webview-src) ===" >&2
    lcov_file="ontology-vscode-extension/webview-src/coverage/lcov.info"
    if command -v npm >/dev/null 2>&1; then
        echo "coverage-wrapper: running the webview's own coverage command (e2e/Playwright + nyc)..." >&2
        if (cd ontology-vscode-extension/e2e && npm run test:coverage && npm run coverage:report); then
            :
        else
            echo "coverage-wrapper: webview coverage command failed to run." >&2
            overall_status=1
        fi
    else
        echo "coverage-wrapper: npm not available in this environment, cannot run the webview coverage command." >&2
        overall_status=1
    fi

    if [ -f "$lcov_file" ]; then
        changed_args=()
        while IFS= read -r f; do
            [ -z "$f" ] && continue
            changed_args+=(--changed-file "$f")
        done <<< "$webview_changed"
        python3 scripts/patch_coverage.py \
            --repo-root "$repo_root" \
            --base "$merge_base" \
            --head "$head_ref" \
            --lcov-file "$lcov_file" \
            --lcov-base-dir "ontology-vscode-extension/webview-src" \
            --min-percent 90 \
            "${changed_args[@]}" || overall_status=1
    else
        echo "coverage-wrapper: FAIL — $lcov_file not found. Missing coverage reports fail verification, not treated as passing (section 9)." >&2
        overall_status=1
    fi
fi

exthost_changed=$(echo "$changed_files" | grep -E '^ontology-vscode-extension/src/' || true)
if [ -n "$exthost_changed" ]; then
    echo "" >&2
    echo "=== extension-host (ontology-vscode-extension/src) ===" >&2
    echo "coverage-wrapper: FAIL — no coverage-producing test command is configured for the extension host (its \"test\" script runs uninstrumented VS Code integration tests). Reporting explicitly as not applicable rather than a false pass, per section 14." >&2
    overall_status=1
fi

java_changed=$(echo "$changed_files" | grep -E '\.java$' | sed -E 's#^(([^/]+/)?[^/]+)/src/.*#\1#' | sort -u || true)
for module in $java_changed; do
    [ -d "$module" ] || continue
    echo "" >&2
    echo "=== $module (Java) ===" >&2
    echo "coverage-wrapper: FAIL — no JaCoCo (or other coverage tool) is configured for any Java module yet. Reporting explicitly as not applicable rather than a false pass, per section 14." >&2
    overall_status=1
done

if [ -z "$webview_changed" ] && [ -z "$exthost_changed" ] && [ -z "$java_changed" ]; then
    echo "coverage-wrapper: no changed files under a recognized sub-project between $merge_base and $head_ref; nothing to do." >&2
fi

echo "" >&2
if [ "$overall_status" -eq 0 ]; then
    echo "coverage-wrapper: PASS" >&2
else
    echo "coverage-wrapper: FAIL" >&2
fi
exit "$overall_status"
