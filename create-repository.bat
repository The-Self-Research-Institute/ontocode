@echo off
REM Create GraphDB Repository for OntoCode

echo ============================================
echo   Creating GraphDB Repository
echo ============================================
echo.

echo Checking if GraphDB is running...
curl -s http://localhost:7200/rest/repositories >nul 2>&1
if errorlevel 1 (
    echo [ERROR] GraphDB is not accessible at http://localhost:7200
    echo Please ensure GraphDB container is running: docker-compose ps
    pause
    exit /b 1
)

echo GraphDB is running!
echo.

echo Creating repository 'ontocode'...
echo.

REM Create repository configuration
curl -X POST http://localhost:7200/rest/repositories ^
  -H "Content-Type: multipart/form-data" ^
  -F "config=@-" << EOF
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

if errorlevel 1 (
    echo.
    echo [ERROR] Failed to create repository via curl
    echo.
    echo Please create manually:
    echo 1. Open http://localhost:7200
    echo 2. Go to Setup → Repositories
    echo 3. Click "Create new repository"
    echo 4. Repository ID: ontocode
    echo 5. Ruleset: owl-horst-optimized
    echo 6. Click Create
    echo.
    pause
    exit /b 1
)

echo.
echo ============================================
echo   Repository Created Successfully!
echo ============================================
echo.
echo Repository: ontocode
echo URL: http://localhost:7200/repositories/ontocode
echo Ruleset: OWL-HORST (Optimized)
echo.
echo You can now upload ontology files!
echo.

pause
