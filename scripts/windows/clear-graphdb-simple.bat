@echo off
echo ╔════════════════════════════════════════╗
echo ║   GraphDB Repository Drop & Recreate   ║
echo ╚════════════════════════════════════════╝
echo.

set GRAPHDB_URL=http://localhost:7200
set REPOSITORY=ontocode

echo Step 1: Deleting repository...
curl -X DELETE "%GRAPHDB_URL%/rest/repositories/%REPOSITORY%" -w "\nHTTP Status: %%{http_code}\n"

echo.
echo Step 2: Waiting 2 seconds...
timeout /t 2 /nobreak >nul

echo.
echo Step 3: Creating new repository...

REM Create repository config
echo ^<?xml version="1.0" encoding="UTF-8"?^> > repo-config.ttl
echo @prefix rep: ^<http://www.openrdf.org/config/repository#^> . >> repo-config.ttl
echo @prefix sr: ^<http://www.openrdf.org/config/repository/sail#^> . >> repo-config.ttl
echo @prefix sail: ^<http://www.openrdf.org/config/sail#^> . >> repo-config.ttl
echo @prefix graphdb: ^<http://www.ontotext.com/config/graphdb#^> . >> repo-config.ttl
echo. >> repo-config.ttl
echo [] a rep:Repository ; >> repo-config.ttl
echo     rep:repositoryID "%REPOSITORY%" ; >> repo-config.ttl
echo     rep:repositoryImpl [ >> repo-config.ttl
echo         rep:repositoryType "graphdb:SailRepository" ; >> repo-config.ttl
echo         sr:sailImpl [ >> repo-config.ttl
echo             sail:sailType "graphdb:Sail" ; >> repo-config.ttl
echo             graphdb:ruleset "owl-horst-optimized" >> repo-config.ttl
echo         ] >> repo-config.ttl
echo     ] . >> repo-config.ttl

curl -X POST "%GRAPHDB_URL%/rest/repositories" ^
    -H "Content-Type: application/x-turtle" ^
    --data-binary "@repo-config.ttl" ^
    -w "\nHTTP Status: %%{http_code}\n"

del repo-config.ttl

echo.
echo ╔════════════════════════════════════════╗
echo ║   ✓ GraphDB Repository Recreated       ║
echo ╚════════════════════════════════════════╝
echo.
pause
