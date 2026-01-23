#!/bin/sh
# init-graphdb.sh

# 1. Set URL: Use the env var if provided, otherwise default to the Docker service name
GRAPHDB_URL="${GRAPHDB_URL:-http://graphdb:7200}"
REPO_ID="ontocode"

echo "--- GraphDB Initialization ---"
echo "Target URL: ${GRAPHDB_URL}"

# 2. Wait Loop: Check if GraphDB is actually reachable
# We loop until we get a valid response or timeout
MAX_RETRIES=12
COUNT=0

echo "Checking connection to GraphDB..."
until curl -s -f --max-time 5 "${GRAPHDB_URL}/rest/repositories" > /dev/null 2>&1; do
  COUNT=$((COUNT+1))
  if [ $COUNT -ge $MAX_RETRIES ]; then
    echo "ERROR: Could not connect to GraphDB at ${GRAPHDB_URL} after 60 seconds."
    exit 1
  fi
  echo "GraphDB not ready yet... waiting 5s ($COUNT/$MAX_RETRIES)"
  sleep 5
done

echo "Connection established!"

# 3. Check for existing repository
if curl -s -f "${GRAPHDB_URL}/repositories/${REPO_ID}" > /dev/null 2>&1; then
  echo "Repository '${REPO_ID}' already exists. Setup complete."
  exit 0
fi

echo "Repository '${REPO_ID}' not found. Attempting to create..."

# 4. Create config file
CONFIG_FILE="/tmp/ontocode-config.ttl"
cat > "${CONFIG_FILE}" << 'EOF'
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix rep: <http://www.openrdf.org/config/repository#> .
@prefix sr: <http://www.openrdf.org/config/repository/sail#> .
@prefix sail: <http://www.openrdf.org/config/sail#> .
@prefix owlim: <http://www.ontotext.com/trree/owlim#> .

[] a rep:Repository ;
    rep:repositoryID "ontocode" ;
    rdfs:label "OntoCode Repository" ;
    rep:repositoryImpl [
        rep:repositoryType "graphdb:FreeSailRepository" ;
        sr:sailImpl [
            sail:sailType "graphdb:FreeSail" ;
            owlim:base-URL "http://example.org/graphdb#" ;
            owlim:defaultNS "" ;
            owlim:entity-index-size "10000000" ;
            owlim:entity-id-size "32" ;
            owlim:imports "" ;
            owlim:repository-type "file-repository" ;
            owlim:ruleset "owl-horst-optimized" ;
            owlim:storage-folder "storage" ;
            owlim:enable-context-index "false" ;
            owlim:enablePredicateList "true" ;
            owlim:in-memory-literal-properties "true" ;
            owlim:enable-literal-index "true" ;
            owlim:check-for-inconsistencies "false" ;
            owlim:disable-sameAs "true" ;
            owlim:query-timeout "0" ;
            owlim:query-limit-results "0" ;
            owlim:throw-QueryEvaluationException-on-timeout "false" ;
            owlim:read-only "false" ;
        ]
    ] .
EOF

# 5. Send Creation Request
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${GRAPHDB_URL}/rest/repositories" \
  -F "config=@${CONFIG_FILE}")

# 6. Verify Success or Fail
if [ "$HTTP_CODE" -eq 201 ] || [ "$HTTP_CODE" -eq 204 ] || [ "$HTTP_CODE" -eq 200 ]; then
  echo "✓ SUCCESS: Repository '${REPO_ID}' created."
  rm -f "${CONFIG_FILE}"
  exit 0
else
  echo "❌ ERROR: Failed to create repository. HTTP Status: ${HTTP_CODE}"
  # Print the actual error message from the server for debugging
  curl -X POST "${GRAPHDB_URL}/rest/repositories" -F "config=@${CONFIG_FILE}"
  rm -f "${CONFIG_FILE}"
  exit 1
fi