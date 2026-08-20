#!/bin/bash

echo "Waiting for GraphDB to start..."
until curl -f http://localhost:7200/rest/repositories > /dev/null 2>&1; do
    echo "GraphDB not ready yet..."
    sleep 5
done

echo "GraphDB is ready!"

cat > /tmp/ontocode-repo.ttl << 'EOF'
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.
@prefix rep: <http://www.openrdf.org/config/repository#>.
@prefix sr: <http://www.openrdf.org/config/repository/sail#>.
@prefix sail: <http://www.openrdf.org/config/sail#>.
@prefix graphdb: <http://www.ontotext.com/config/graphdb#>.

[] a rep:Repository ;
    rep:repositoryID "ontocode" ;
    rdfs:label "OntoCode Repository" ;
    rep:repositoryImpl [
        rep:repositoryType "graphdb:SailRepository" ;
        sr:sailImpl [
            sail:sailType "graphdb:Sail" ;
            graphdb:read-only "false" ;
            graphdb:ruleset "owl-horst-optimized" ;
            graphdb:query-timeout "0" ;
            graphdb:throw-query-evaluation-exception-on-timeout "false" ;
            graphdb:check-for-inconsistencies "false" ;
            graphdb:enable-context-index "true" ;
            graphdb:enable-predicate-list "true" ;
            graphdb:base-URL "http://example.com/ont#" ;
        ]
    ].
EOF

echo "Creating ontocode repository..."
curl -X POST http://localhost:7200/rest/repositories \
    -H "Content-Type: application/x-turtle" \
    --data-binary @/tmp/ontocode-repo.ttl

echo "Repository setup complete!"