@echo off
echo Creating GraphDB repository 'ontocode' with inference DISABLED...
echo.

docker exec ontocode-graphdb sh -c "cat > /tmp/repo-config.ttl << 'EOFCONFIG'
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix rep: <http://www.openrdf.org/config/repository#> .
@prefix sr: <http://www.openrdf.org/config/repository/sail#> .
@prefix sail: <http://www.openrdf.org/config/sail#> .
@prefix graphdb: <http://www.ontotext.com/config/graphdb#> .

[] a rep:Repository ;
    rep:repositoryID \"ontocode\" ;
    rdfs:label \"OntoCode Repository - No Inference\" ;
    rep:repositoryImpl [
        rep:repositoryType \"graphdb:SailRepository\" ;
        sr:sailImpl [
            sail:sailType \"graphdb:Sail\" ;
            graphdb:ruleset \"empty\" ;
            graphdb:disable-sameAs \"true\" ;
            graphdb:check-for-inconsistencies \"false\" ;
            graphdb:in-memory-literal-properties \"true\" ;
            graphdb:enable-literal-index \"true\" ;
            graphdb:entity-index-size \"10000000\" ;
            graphdb:enablePredicateList \"true\" ;
            graphdb:read-only \"false\" ;
        ]
    ] .
EOFCONFIG
curl -X POST --header 'Content-Type: multipart/form-data' -F 'config=@/tmp/repo-config.ttl' http://localhost:7200/rest/repositories"

echo.
echo Verifying repository creation...
docker exec ontocode-graphdb curl -s http://localhost:7200/rest/repositories

echo.
echo Done!
pause
