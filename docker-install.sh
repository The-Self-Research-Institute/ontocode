#!/bin/bash
# ========================================
# OntoCode - Docker-Only Installation
# No Node.js Required - Everything in Docker!
# ========================================

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m' # No Color

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  OntoCode Docker-Only Installation${NC}"
echo -e "${CYAN}  (No Node.js Required!)${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# Check if Docker is installed
echo -e "${YELLOW}[1/5] Checking Docker...${NC}"
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

# Ensure data directory exists
echo ""
echo -e "${YELLOW}[2/5] Preparing workspace directory...${NC}"
if [ ! -d "data/projects" ]; then
    echo -e "${GRAY}Creating data/projects directory...${NC}"
    mkdir -p data/projects
fi
echo -e "${GREEN}[OK] Workspace directory ready${NC}"

# Stop any existing containers
echo ""
echo -e "${YELLOW}[3/5] Cleaning up existing containers...${NC}"
docker compose down -v &> /dev/null || true
echo -e "${GREEN}[OK] Cleanup complete${NC}"

# Build and start all Docker services
echo ""
echo -e "${YELLOW}[4/5] Building and starting all services...${NC}"
echo -e "${GRAY}This includes: MongoDB, GraphDB, Auth, Gateway, Editor, SWRL, Plugins, and VS Code Web${NC}"
echo -e "${YELLOW}First run may take 5-10 minutes...${NC}"
docker compose up -d --build

if [ $? -ne 0 ]; then
    echo -e "${RED}[ERROR] Failed to start Docker services${NC}"
    exit 1
fi
echo -e "${GREEN}[OK] All Docker services started successfully${NC}"

# Wait for services to be ready
echo ""
echo -e "${YELLOW}[5/5] Waiting for all services to initialize...${NC}"
echo -e "${GRAY}This includes starting the VS Code web server...${NC}"
sleep 45
echo -e "${GREEN}[OK] Services should be ready${NC}"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Installation Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${CYAN}All services running in Docker:${NC}"
echo ""
echo -e "${YELLOW}  PRIMARY ACCESS:${NC}"
echo -e "  - VS Code Web Editor:  ${CYAN}http://localhost:3000${NC}"
echo -e "     ${GRAY}(Open this in your browser to start editing)${NC}"
echo ""
echo -e "${YELLOW}  BACKEND SERVICES:${NC}"
echo "  - API Gateway:         http://localhost:80"
echo "  - Auth Service:        http://localhost:8086"
echo "  - OWL Editor:          http://localhost:8083"
echo "  - SWRL Service:        http://localhost:8084"
echo "  - Plugin Service:      http://localhost:8087"
echo "  - MongoDB:             mongodb://localhost:27017"
echo "  - GraphDB:             http://localhost:7200"
echo ""
echo -e "${GREEN}========================================${NC}"
echo ""

# Try to open browser (works on most Linux systems)
echo -e "${YELLOW}Opening VS Code Web Editor in your browser...${NC}"
sleep 2

if command -v xdg-open &> /dev/null; then
    xdg-open http://localhost:3000 &> /dev/null || true
elif command -v open &> /dev/null; then
    open http://localhost:3000 &> /dev/null || true
else
    echo -e "${GRAY}Please open http://localhost:3000 in your browser${NC}"
fi

echo ""
echo -e "${CYAN}Useful commands:${NC}"
echo "  Stop all services:     docker compose down"
echo "  View web editor logs:  docker compose logs -f vscode-web"
echo "  View all logs:         docker compose logs -f"
echo "  Restart web editor:    docker compose restart vscode-web"
echo ""
