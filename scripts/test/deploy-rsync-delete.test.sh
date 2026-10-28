#!/usr/bin/env bash
set -euo pipefail

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
src="$work/src"
dst="$work/dst"
mkdir -p "$src/shared" "$src/ontology-swrl/src/main" "$dst"

echo pom > "$src/pom.xml"
echo a > "$src/shared/Keep.java"
echo b > "$src/ontology-swrl/src/main/Keep.java"
echo pom > "$src/ontology-swrl/pom.xml"

paths=(pom.xml shared/ ontology-swrl/pom.xml ontology-swrl/src/)
flags=(-azR --delete --itemize-changes --exclude target --exclude node_modules)

(cd "$src" && rsync "${flags[@]}" "${paths[@]}" "$dst/" > /dev/null)

echo stale > "$dst/ontology-swrl/src/main/SwrlHistoryReporter.java"
echo stale > "$dst/shared/Old.java"
mkdir -p "$dst/ontology-swrl/target" "$dst/ontology-swrl/src/node_modules" "$dst/data"
echo built > "$dst/ontology-swrl/target/app.jar"
echo mod > "$dst/ontology-swrl/src/node_modules/m.js"
echo secret > "$dst/.env"
echo vol > "$dst/data/x"
echo compose > "$dst/docker-compose.remote-build.yml"

out=$(cd "$src" && rsync "${flags[@]}" -n "${paths[@]}" "$dst/")
[[ -f "$dst/ontology-swrl/src/main/SwrlHistoryReporter.java" ]] || { echo "FAIL: dry run deleted a file"; exit 1; }
[[ $(grep -c '^\*deleting' <<< "$out") -eq 2 ]] || { echo "FAIL: dry run should list 2 deletions"; echo "$out"; exit 1; }

(cd "$src" && rsync "${flags[@]}" "${paths[@]}" "$dst/" > /dev/null)

fail=0
check_gone() { [[ ! -e "$1" ]] || { echo "FAIL: still present: $1"; fail=1; }; }
check_kept() { [[ -e "$1" ]] || { echo "FAIL: wrongly removed: $1"; fail=1; }; }
check_gone "$dst/ontology-swrl/src/main/SwrlHistoryReporter.java"
check_gone "$dst/shared/Old.java"
check_kept "$dst/ontology-swrl/src/main/Keep.java"
check_kept "$dst/shared/Keep.java"
check_kept "$dst/.env"
check_kept "$dst/data/x"
check_kept "$dst/docker-compose.remote-build.yml"
check_kept "$dst/ontology-swrl/target/app.jar"
check_kept "$dst/ontology-swrl/src/node_modules/m.js"

[[ $fail -eq 0 ]] || exit 1
echo "PASS"
