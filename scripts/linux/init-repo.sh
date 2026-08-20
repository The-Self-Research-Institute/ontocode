#!/bin/sh

REPO_ID="ontocode"
GRAPHDB_URL="http://graphdb:7200"

echo "--- GraphDB Initialization ---"
echo "Target URL: $GRAPHDB_URL"

for i in $(seq 1 30); do
    if curl -s -f "$GRAPHDB_URL/rest/repositories" > /dev/null; then
        echo "GraphDB is UP!"
        break
    fi
    echo "GraphDB not ready yet... waiting 5s ($i/30)"
    sleep 5
done

if curl -s "$GRAPHDB_URL/rest/repositories" | grep -q "$REPO_ID"; then
    echo "Repository '$REPO_ID' already exists."
else
    echo "Creating repository '$REPO_ID' with inference DISABLED..."
    cat <<'EOF' > /tmp/repo-config.ttl
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix rep: <http://www.openrdf.org/config/repository#> .
@prefix sr: <http://www.openrdf.org/config/repository/sail#> .
@prefix sail: <http://www.openrdf.org/config/sail#> .
@prefix graphdb: <http://www.ontotext.com/config/graphdb#> .

[] a rep:Repository ;
    rep:repositoryID "ontocode" ;
    rdfs:label "OntoCode Repository (No Inference)" ;
    rep:repositoryImpl [
        rep:repositoryType "graphdb:SailRepository" ;
        sr:sailImpl [
            sail:sailType "graphdb:Sail" ;
            graphdb:ruleset "empty" ;
            graphdb:disable-sameAs "true" ;
            graphdb:check-for-inconsistencies "false" ;
            graphdb:in-memory-literal-properties "true" ;
            graphdb:enable-literal-index "true" ;
            graphdb:entity-index-size "10000000" ;
            graphdb:enablePredicateList "true" ;
            graphdb:read-only "false" ;
        ]
    ] .
EOF
    curl -X POST --header "Content-Type: multipart/form-data" -F "config=@/tmp/repo-config.ttl" "$GRAPHDB_URL/rest/repositories"
    echo ""
    echo "Repository '$REPO_ID' created successfully with inference DISABLED (ruleset: empty)."
fi

echo "--- Initialization Complete ---"