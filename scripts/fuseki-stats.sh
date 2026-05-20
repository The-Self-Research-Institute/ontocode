#!/usr/bin/env bash
# fuseki-stats.sh — Generate TDB2 statistics for the Fuseki query optimizer.
#
# TDB2's ARQ query planner uses a stats.opt file containing predicate
# frequencies to choose optimal join ordering. Without it, the planner
# falls back to heuristics, which can produce slow plans for complex queries.
#
# HOW TO RUN (on the EC2 host):
#   1. Stop Fuseki (required — tdb2.tdbstats needs exclusive access to the files)
#   2. Run this script
#   3. Start Fuseki
#
# Example:
#   docker compose stop fuseki
#   bash scripts/fuseki-stats.sh
#   docker compose start fuseki
#
# After stats are generated, Fuseki picks them up automatically on next start.
# Re-run this script whenever your dataset grows significantly (e.g. after
# adding a large ontology). Stats are stale once triple count changes by >20%.

set -euo pipefail

CONTAINER_NAME="${FUSEKI_CONTAINER:-ontocode-fuseki}"
TDB2_LOC="${TDB2_LOC:-/fuseki/databases/ontocode}"
STATS_FILE="$TDB2_LOC/stats.opt"
IMAGE="${FUSEKI_IMAGE:-stain/jena-fuseki:5.0.0}"

echo "=== TDB2 Statistics Generator ==="
echo "Container : $CONTAINER_NAME"
echo "TDB2 path : $TDB2_LOC"
echo "Stats file: $STATS_FILE"
echo ""

# Check that Fuseki is stopped — stats generation requires no active TDB2 lock
if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "ERROR: Fuseki container '$CONTAINER_NAME' is still running."
    echo "Stop it first:  docker compose stop fuseki"
    exit 1
fi

echo "Fuseki is stopped. Generating statistics..."

# Run tdb2.tdbstats using a throw-away container that shares the fuseki-data volume.
# The stats.opt file is written directly into the TDB2 dataset directory.
docker run --rm \
    -v ontocode_fuseki-data:/fuseki/databases \
    --entrypoint /bin/sh \
    "$IMAGE" \
    -c "/jena-fuseki/bin/tdb2.tdbstats --loc $TDB2_LOC > $STATS_FILE && echo 'stats.opt written' && wc -l $STATS_FILE"

echo ""
echo "=== Done ==="
echo "stats.opt generated at $STATS_FILE inside the fuseki-data volume."
echo "Start Fuseki to apply:  docker compose start fuseki"
