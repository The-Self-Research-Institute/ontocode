#!/bin/bash
LOGDIR=/tmp/tmp.6PXn6CYouG
OUT=/tmp/ontocode-watch.log
CUTOFF=$((SECONDS + 90*60))
POLL=75
echo "WATCH_START $(date -Iseconds) cutoff_in=90m" | tee "$OUT"
while true; do
  ALIVE=0
  pgrep -f "deploy-coretopia-release.sh" >/dev/null 2>&1 && ALIVE=1
  pgrep -f "build-and-push.sh ontocode" >/dev/null 2>&1 && ALIVE=1
  if pgrep -af "docker buildx build" 2>/dev/null | grep -v "bash -lc\|pgrep\|grep\|ontocode-watch" >/dev/null; then
    ALIVE=1
  fi

  CUR=$(grep -E "STEP [0-9]+/8|Building " "$LOGDIR/dev-web.log" 2>/dev/null | tail -5)
  PUSHED=$(grep -E "naming to docker.io|pushed .* digest|DONE" "$LOGDIR/dev-web.log" 2>/dev/null | grep -E "auth|gateway|editor|reasoner|swrl|plugin|web|STEP" | tail -15)
  TAIL=$(tail -6 "$LOGDIR/dev-web.log" 2>/dev/null | tr -d "\r")
  BUILDX=$(pgrep -af "docker buildx build" 2>/dev/null | grep -v "bash -lc\|pgrep\|grep\|ontocode-watch" | head -2)
  NOW=$(date +%H:%M:%S)
  REMAIN=$((CUTOFF - SECONDS))
  {
    echo "---- $NOW remain=${REMAIN}s alive=$ALIVE ----"
    echo "BUILDX: ${BUILDX:-none}"
    echo "CUR:"
    echo "$CUR"
    echo "RECENT_PUSH_HINTS:"
    echo "$PUSHED"
    echo "TAIL:"
    echo "$TAIL"
  } | tee -a "$OUT"

  if [ "$ALIVE" -eq 0 ]; then
    echo "PROCESSES_EXITED $(date -Iseconds)" | tee -a "$OUT"
    echo "=== FINAL WEB ===" | tee -a "$OUT"
    grep -E "STEP [0-9]+/8|naming to docker.io|pushed|FAILED|ERROR:|finished|SUCCESS" "$LOGDIR/dev-web.log" 2>/dev/null | tail -80 | tee -a "$OUT"
    echo "=== FINAL DESKTOP ===" | tee -a "$OUT"
    grep -E "finished|ERROR|FAIL|success|skipped" "$LOGDIR/dev-desktop.log" 2>/dev/null | tail -25 | tee -a "$OUT"
    echo "=== LOG SIZES ===" | tee -a "$OUT"
    ls -la "$LOGDIR"/*.log 2>/dev/null | tee -a "$OUT"
    echo "WATCH_DONE exit=0"
    exit 0
  fi

  if [ $SECONDS -ge $CUTOFF ]; then
    echo "CUTOFF_REACHED $(date -Iseconds)" | tee -a "$OUT"
    echo "=== LAST WEB PROGRESS ===" | tee -a "$OUT"
    grep -E "STEP [0-9]+/8|naming to docker.io|pushed|FAILED|ERROR:|Building " "$LOGDIR/dev-web.log" 2>/dev/null | tail -50 | tee -a "$OUT"
    echo "TAIL:" | tee -a "$OUT"
    tail -20 "$LOGDIR/dev-web.log" 2>/dev/null | tee -a "$OUT"
    echo "PROCS:" | tee -a "$OUT"
    pgrep -af "deploy-coretopia|build-and-push.sh|docker buildx" 2>/dev/null | grep -v "bash -lc\|pgrep\|ontocode-watch" | tee -a "$OUT"
    echo "WATCH_DONE exit=2"
    exit 2
  fi

  sleep $POLL
done