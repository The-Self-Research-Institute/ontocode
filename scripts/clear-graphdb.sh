#!/bin/bash

GRAPHDB_URL="http://localhost:7200"
REPOSITORY="ontocode"

echo "=== GraphDB Cleanup Script ==="
echo "GraphDB URL: $GRAPHDB_URL"
echo "Repository: $REPOSITORY"
echo ""

echo "Checking if GraphDB is accessible..."
if ! curl -s -f "${GRAPHDB_URL}/rest/repositories" > /dev/null; then
    echo "ERROR: Cannot connect to GraphDB at ${GRAPHDB_URL}"
    echo "Please ensure GraphDB is running."
    exit 1
fi

echo "✓ GraphDB is accessible"
echo ""

echo "Checking current data..."
QUERY="SELECT (COUNT(*) as ?count) WHERE { ?s ?p ?o }"
COUNT=$(curl -s -X POST "${GRAPHDB_URL}/repositories/${REPOSITORY}" \
    -H "Accept: application/sparql-results+json" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "query=${QUERY}" | \
    grep -o '"value":"[0-9]*"' | head -1 | grep -o '[0-9]*')

echo "Current triple count: ${COUNT:-0}"
echo ""

echo "=== Starting cleanup ==="
echo "Clearing all triples from repository: ${REPOSITORY}"

UPDATE_QUERY="DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }"

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${GRAPHDB_URL}/repositories/${REPOSITORY}/statements" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "update=${UPDATE_QUERY}")

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

if [ "$HTTP_CODE" -eq 204 ] || [ "$HTTP_CODE" -eq 200 ]; then
    echo "✓ Successfully cleared all triples"
else
    echo "✗ Failed to clear triples (HTTP ${HTTP_CODE})"
    echo "Response: ${BODY}"
    exit 1
fi

echo ""
echo "Verifying cleanup..."
COUNT_AFTER=$(curl -s -X POST "${GRAPHDB_URL}/repositories/${REPOSITORY}" \
    -H "Accept: application/sparql-results+json" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "query=${QUERY}" | \
    grep -o '"value":"[0-9]*"' | head -1 | grep -o '[0-9]*')

echo "Triple count after cleanup: ${COUNT_AFTER:-0}"
echo ""
echo "=== Cleanup complete ==="
