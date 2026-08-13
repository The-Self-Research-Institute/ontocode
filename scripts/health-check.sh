#!/bin/bash

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

echo ""
echo "Checking service health..."
echo ""

check_service() {
    local name=$1
    local url=$2
    
    if curl -s "$url" > /dev/null 2>&1; then
        echo -e "${GREEN}✓${NC} $name - UP"
    else
        echo -e "${RED}✗${NC} $name - DOWN"
    fi
}

check_service "MongoDB        " "http://localhost:27017"

GRAPHDB_REPO="${GRAPHDB_REPOSITORY:-ontocode}"
if curl -s http://localhost:7200/rest/repositories > /dev/null 2>&1; then
    check_service "GraphDB        " "http://localhost:7200/rest/repositories"
    

    if curl -s http://localhost:7200/rest/repositories | grep -q "\"$GRAPHDB_REPO\""; then
        echo -e "${GREEN}  ✓${NC} Repository '$GRAPHDB_REPO' exists"
    else
        echo -e "${RED}  ✗${NC} Repository '$GRAPHDB_REPO' NOT found"
        echo -e "     Create it at: http://localhost:7200/repository"
        echo -e "     See: GRAPHDB_SETUP.md for instructions"
    fi
elif curl -s http://localhost:3030 > /dev/null 2>&1; then
    check_service "Fuseki         " "http://localhost:3030"
else
    echo -e "${RED}✗${NC} SPARQL Endpoint - DOWN"
    echo -e "   GraphDB required: See GRAPHDB_SETUP.md"
fi

check_service "Auth Service   " "http://localhost:8086/actuator/health"
check_service "Gateway        " "http://localhost:8082/actuator/health"
check_service "OWL Editor     " "http://localhost:8083/actuator/health"
check_service "SWRL Service   " "http://localhost:8084/actuator/health"

echo ""