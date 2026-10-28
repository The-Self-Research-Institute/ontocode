#!/usr/bin/env bash
set -u

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/prune-heapdumps.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail=0

check() {
  if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}

listing() { (cd "$1" && ls -1 | sort | tr '\n' ',' ); }

D="$WORK/mixed"
mkdir "$D"
touch -d '1 hour ago' "$D/new1.hprof"
touch -d '1 day ago' "$D/new 2.hprof"
touch -d '2 days ago' "$D/recent3.hprof"
touch -d '10 days ago' "$D/old4.hprof"
touch -d '30 days ago' "$D/old5.hprof"
touch -d '60 days ago' "$D/notes.txt"

bash "$SCRIPT" --dry-run "$D" >/dev/null
check "dry-run removes nothing" "new 2.hprof,new1.hprof,notes.txt,old4.hprof,old5.hprof,recent3.hprof," "$(listing "$D")"

out="$(bash "$SCRIPT" "$D")"
check "keeps newest 2, drops rest, ignores non-hprof" "new 2.hprof,new1.hprof,notes.txt," "$(listing "$D")"
case "$out" in *"deleted $D/old5.hprof"*) echo "PASS prints deletions" ;; *) echo "FAIL prints deletions"; fail=1 ;; esac

D2="$WORK/stale"
mkdir "$D2"
touch -d '20 days ago' "$D2/a.hprof"
touch -d '9 days ago' "$D2/b.hprof"
bash "$SCRIPT" "$D2" >/dev/null
check "old dumps removed even within keep count" "" "$(listing "$D2")"

D3="$WORK/fresh"
mkdir "$D3"
touch -d '1 day ago' "$D3/a.hprof"
touch -d '2 days ago' "$D3/b.hprof"
bash "$SCRIPT" "$D3" >/dev/null
check "two recent dumps kept" "a.hprof,b.hprof," "$(listing "$D3")"

mkdir "$WORK/empty"
bash "$SCRIPT" "$WORK/empty" >/dev/null
check "empty dir exit code" "0" "$?"
bash "$SCRIPT" "$WORK/missing" >/dev/null
check "missing dir exit code" "0" "$?"

exit $fail
