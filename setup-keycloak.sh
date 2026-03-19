#!/bin/bash

# OntoCode with Keycloak - Quick Setup Script
# This script sets up OntoCode with Keycloak OIDC authentication

set -e

echo "=================================================="
echo "OntoCode with Keycloak Setup"
echo "=================================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check if docker is installed
if ! command -v docker &> /dev/null; then
    echo -e "${RED}❌ Docker is not installed. Please install Docker first.${NC}"
    exit 1
fi

# Check if docker-compose is installed
if ! command -v docker-compose &> /dev/null; then
    echo -e "${RED}❌ Docker Compose is not installed. Please install Docker Compose first.${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Docker and Docker Compose are installed${NC}"
echo ""

# Step 1: Start Keycloak and database
echo -e "${YELLOW}Step 1: Starting Keycloak and PostgreSQL...${NC}"
docker-compose -f docker-compose.keycloak.yml up -d keycloak-db keycloak

echo "Waiting for Keycloak to start (this may take 60-90 seconds)..."
sleep 30

# Check if Keycloak is healthy
KEYCLOAK_HEALTH_CHECK=0
for i in {1..12}; do
    if docker-compose -f docker-compose.keycloak.yml ps keycloak | grep -q "healthy"; then
        KEYCLOAK_HEALTH_CHECK=1
        break
    fi
    echo "Waiting for Keycloak to be ready... ($i/12)"
    sleep 5
done

if [ $KEYCLOAK_HEALTH_CHECK -eq 0 ]; then
    echo -e "${RED}❌ Keycloak failed to start. Check logs with: docker-compose -f docker-compose.keycloak.yml logs keycloak${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Keycloak is running${NC}"
echo "   Admin console: http://localhost:8080"
echo "   Username: admin"
echo "   Password: admin"
echo ""

# Step 2: Configure Keycloak (manual step)
echo -e "${YELLOW}Step 2: Keycloak Configuration${NC}"
echo "Please complete the following in Keycloak Admin Console:"
echo ""
echo "1. Open: http://localhost:8080"
echo "2. Login with admin/admin"
echo "3. Create a realm named: ontocode"
echo "4. Create a client:"
echo "   - Client ID: ontocode-auth"
echo "   - Client authentication: ON"
echo "   - Valid redirect URIs: http://localhost:8086/*"
echo "5. Go to Credentials tab and copy the Client Secret"
echo "6. Create a test user with email and password"
echo ""
read -p "Press Enter when Keycloak configuration is complete..."
echo ""

# Get client secret from user
echo -e "${YELLOW}Enter the Keycloak Client Secret:${NC}"
read -s KEYCLOAK_CLIENT_SECRET
echo ""

if [ -z "$KEYCLOAK_CLIENT_SECRET" ]; then
    echo -e "${RED}❌ Client secret cannot be empty${NC}"
    exit 1
fi

# Create .env file if it doesn't exist
if [ ! -f .env ]; then
    echo "Creating .env file..."
    cat > .env << EOF
# Keycloak Configuration
KEYCLOAK_CLIENT_SECRET=${KEYCLOAK_CLIENT_SECRET}

# Admin Configuration
ADMIN_PASSWORD=admin123

# SMTP Configuration (optional - leave empty to skip)
SMTP_HOST=
SMTP_PORT=
SMTP_USERNAME=
SMTP_PASSWORD=
EOF
    echo -e "${GREEN}✅ .env file created${NC}"
else
    # Update existing .env file
    if grep -q "KEYCLOAK_CLIENT_SECRET" .env; then
        sed -i "s/KEYCLOAK_CLIENT_SECRET=.*/KEYCLOAK_CLIENT_SECRET=${KEYCLOAK_CLIENT_SECRET}/" .env
    else
        echo "KEYCLOAK_CLIENT_SECRET=${KEYCLOAK_CLIENT_SECRET}" >> .env
    fi
    echo -e "${GREEN}✅ .env file updated${NC}"
fi
echo ""

# Step 3: Start all services
echo -e "${YELLOW}Step 3: Starting all OntoCode services...${NC}"
docker-compose -f docker-compose.keycloak.yml up -d

echo "Waiting for all services to start..."
sleep 30

# Check service health
echo ""
echo -e "${YELLOW}Checking service health...${NC}"

services=("mongodb" "graphdb" "keycloak" "ontology-auth" "ontology-editor" "ontology-gateway")
all_healthy=1

for service in "${services[@]}"; do
    if docker-compose -f docker-compose.keycloak.yml ps $service | grep -q "healthy\|Up"; then
        echo -e "${GREEN}✅ $service is running${NC}"
    else
        echo -e "${RED}❌ $service is not healthy${NC}"
        all_healthy=0
    fi
done

echo ""

if [ $all_healthy -eq 1 ]; then
    echo -e "${GREEN}=================================================="
    echo "✅ OntoCode with Keycloak is running!"
    echo "==================================================${NC}"
    echo ""
    echo "Access the services:"
    echo "  • Keycloak Admin:   http://localhost:8080 (admin/admin)"
    echo "  • OntoCode Gateway: http://localhost:80"
    echo "  • Auth Service:     http://localhost:8086"
    echo "  • Editor Service:   http://localhost:8083"
    echo "  • GraphDB:          http://localhost:7200"
    echo "  • MongoDB:          mongodb://localhost:27017"
    echo ""
    echo "Test OIDC authentication:"
    echo "  curl http://localhost:8086/api/auth/oidc/providers"
    echo ""
    echo "Login with Keycloak:"
    echo "  Open: http://localhost:8086/oauth2/authorization/keycloak"
    echo "  Or use VS Code command: OntoCode: Login with OIDC/SSO"
    echo ""
    echo "View logs:"
    echo "  docker-compose -f docker-compose.keycloak.yml logs -f [service-name]"
    echo ""
    echo "Stop all services:"
    echo "  docker-compose -f docker-compose.keycloak.yml down"
    echo ""
else
    echo -e "${RED}=================================================="
    echo "⚠️  Some services are not healthy"
    echo "==================================================${NC}"
    echo ""
    echo "Check logs for details:"
    echo "  docker-compose -f docker-compose.keycloak.yml logs"
    echo ""
    echo "Try restarting failed services:"
    echo "  docker-compose -f docker-compose.keycloak.yml restart [service-name]"
fi
