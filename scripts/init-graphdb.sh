#!/bin/bash
# GraphDB Repository Initialization Script
# This script creates the 'ontocode' repository if it doesn't exist

GRAPHDB_URL="http://graphdb:7200"

echo "Waiting for GraphDB to be ready..."
until curl -sf ${GRAPHDB_URL}/rest/repositories > /dev/null 2>&1; do
  echo "GraphDB not ready yet, waiting..."
  sleep 5
done

echo "GraphDB is ready!"

# Check if repository exists
if curl -sf ${GRAPHDB_URL}/repositories/ontocode > /dev/null 2>&1; then
  echo "Repository 'ontocode' already exists, skipping creation."
  exit 0
fi

echo "Creating 'ontocode' repository..."

# Create repository configuration
cat > /tmp/ontocode-config.ttl << 'EOF'
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

# Upload configuration to GraphDB
curl -X POST ${GRAPHDB_URL}/rest/repositories \
  -H "Content-Type: multipart/form-data" \
  -F "config=@/tmp/ontocode-config.ttl" \
  > /dev/null 2>&1

if [ $? -eq 0 ]; then
  echo "✓ Repository 'ontocode' created successfully!"
else
  echo "✗ Failed to create repository. Please create it manually at ${GRAPHDB_URL}"
  exit 1
fi

# Cleanup
rm -f /tmp/ontocode-config.ttl

echo "GraphDB initialization complete."
