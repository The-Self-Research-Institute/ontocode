#!/bin/bash
# ========================================
# OntoCode - One-Click Installation (Linux/Mac)
# ========================================

set -e

echo ""
echo "========================================"
echo "  OntoCode One-Click Installation"
echo "========================================"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Check if Docker is installed
echo -e "${YELLOW}[1/6] Checking Docker...${NC}"
if ! command -v docker &> /dev/null; then
    echo -e "${RED}[ERROR] Docker is not installed${NC}"
    echo "Please install Docker from: https://www.docker.com/products/docker-desktop"
    exit 1
fi

# Check if Docker is running
if ! docker ps &> /dev/null; then
    echo -e "${RED}[ERROR] Docker is not running${NC}"
    echo "Please start Docker and try again"
    exit 1
fi
echo -e "${GREEN}[OK] Docker is running${NC}"

# Check if Node.js is installed
echo ""
echo -e "${YELLOW}[2/6] Checking Node.js...${NC}"
if ! command -v node &> /dev/null; then
    echo -e "${RED}[ERROR] Node.js is not installed${NC}"
    echo "Please install Node.js from: https://nodejs.org/"
    exit 1
fi
NODE_VERSION=$(node --version)
echo -e "${GREEN}[OK] Node.js is installed ($NODE_VERSION)${NC}"

# Stop any existing containers
echo ""
echo -e "${YELLOW}[3/6] Cleaning up existing containers...${NC}"
docker compose down -v &> /dev/null || true
echo -e "${GREEN}[OK] Cleanup complete${NC}"

# Build and start all Docker services
echo ""
echo -e "${YELLOW}[4/6] Building and starting Docker services...${NC}"
echo "This may take several minutes on first run..."
docker compose up -d --build

if [ $? -ne 0 ]; then
    echo -e "${RED}[ERROR] Failed to start Docker services${NC}"
    exit 1
fi
echo -e "${GREEN}[OK] Docker services started successfully${NC}"

# Wait for services to be ready
echo ""
echo -e "${YELLOW}[5/6] Waiting for services to initialize...${NC}"
sleep 30
echo -e "${GREEN}[OK] Services should be ready${NC}"

# Build and launch VS Code web extension
echo ""
echo -e "${YELLOW}[6/6] Building and launching VS Code Web Extension...${NC}"
cd ontology-vscode-extension

# Check if node_modules exists, install if not
if [ ! -d "node_modules" ]; then
    echo "Installing extension dependencies..."
    npm install
    if [ $? -ne 0 ]; then
        echo -e "${RED}[ERROR] Failed to install dependencies${NC}"
        cd ..
        exit 1
    fi
fi

# Build web extension bundle
echo "Building web extension bundle..."
npm run bundle:web
if [ $? -ne 0 ]; then
    echo -e "${RED}[ERROR] Failed to build extension bundle${NC}"
    cd ..
    exit 1
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Installation Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${CYAN}Services running at:${NC}"
echo "  - API Gateway:     http://localhost:80"
echo "  - Auth Service:    http://localhost:8086"
echo "  - OWL Editor:      http://localhost:8083"
echo "  - SWRL Service:    http://localhost:8084"
echo "  - Plugin Service:  http://localhost:8087"
echo "  - MongoDB:         mongodb://localhost:27017"
echo "  - GraphDB:         http://localhost:7200"
echo ""
echo -e "${YELLOW}Starting VS Code Web Editor...${NC}"
echo "The editor will open in your default browser."
echo ""
echo -e "${GREEN}========================================${NC}"

# Launch VS Code web extension
npm run test-web

cd ..

echo ""
echo -e "${CYAN}To stop all services, run: docker compose down${NC}"
echo ""
