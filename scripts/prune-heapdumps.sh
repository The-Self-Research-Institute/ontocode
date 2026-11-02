#!/usr/bin/env bash
set -u

KEEP=2
MAX_AGE_DAYS=7
DRY_RUN=0
DIR=""

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    *) DIR="$arg" ;;
  esac
done

DIR="${DIR:-/app/heapdumps}"

if [ ! -d "$DIR" ]; then
  echo "prune-heapdumps: $DIR does not exist, nothing to do"
  exit 0
fi

cutoff=$(( $(date +%s) - MAX_AGE_DAYS * 86400 ))
index=0
removed=0

remove() {
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "prune-heapdumps: would delete $1 ($2)"
  else
    if rm -f -- "$1"; then
      echo "prune-heapdumps: deleted $1 ($2)"
    fi
  fi
  removed=$((removed + 1))
}

while IFS= read -r -d '' entry; do
  mtime="${entry%%$'\t'*}"
  file="${entry#*$'\t'}"
  index=$((index + 1))
  if [ "$index" -gt "$KEEP" ]; then
    remove "$file" "older than newest $KEEP"
  elif [ "$mtime" -lt "$cutoff" ]; then
    remove "$file" "older than $MAX_AGE_DAYS days"
  fi
done < <(find "$DIR" -maxdepth 1 -type f -name '*.hprof' -printf '%T@\t%p\0' | sort -z -t$'\t' -k1,1nr | sed -z 's/^\([0-9]*\)\.[0-9]*/\1/')

echo "prune-heapdumps: $removed file(s) $([ "$DRY_RUN" -eq 1 ] && echo 'would be ')removed from $DIR"
exit 0
