#!/usr/bin/env bash

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

if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "ERROR: Fuseki container '$CONTAINER_NAME' is still running."
    echo "Stop it first:  docker compose stop fuseki"
    exit 1
fi

echo "Fuseki is stopped. Generating statistics..."

docker run --rm \
    -v ontocode_fuseki-data:/fuseki/databases \
    --entrypoint /bin/sh \
    "$IMAGE" \
    -c "/jena-fuseki/bin/tdb2.tdbstats --loc $TDB2_LOC > $STATS_FILE && echo 'stats.opt written' && wc -l $STATS_FILE"

echo ""
echo "=== Done ==="
echo "stats.opt generated at $STATS_FILE inside the fuseki-data volume."
echo "Start Fuseki to apply:  docker compose start fuseki"
