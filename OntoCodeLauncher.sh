#!/bin/bash
# ========================================
# OntoCode - One-Click Installation
# For Linux/Mac
# ========================================

REGISTRY="sindhujacoretopia"
VERSION="latest"

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Configuration
MONGO_INITDB_ROOT_USERNAME="admin"
MONGO_INITDB_ROOT_PASSWORD="changeme123"
MONGO_INITDB_DATABASE="ontocode"
JWT_SECRET="AVjDXKlnmDh4K26HTaEwHvNBN3IWXpbcGxt+2sveqGA="
GRAPHDB_ADMIN_PASSWORD="admin"

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}   OntoCode One-Click Installation${NC}"
echo -e "${CYAN}   Registry: ${REGISTRY}${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# [1/6] Check Docker
echo "[1/6] Checking Docker..."
if ! command -v docker &> /dev/null; then
    echo -e "${RED}[ERROR] Docker is not installed.${NC}"
    echo "Install from: https://www.docker.com/products/docker-desktop"
    read -p "Press Enter to exit..."
    exit 1
fi

if ! docker ps &> /dev/null; then
    echo -e "${RED}[ERROR] Docker is not running. Please start Docker.${NC}"
    read -p "Press Enter to exit..."
    exit 1
fi
echo -e "${GREEN}[OK] Docker is running${NC}"

# [2/6] Prepare workspace
echo ""
echo "[2/6] Preparing workspace..."
mkdir -p data/projects

# Create .env file with direct configuration
echo "[INFO] Creating configuration..."
cat > .env << EOF
# MongoDB Configuration
MONGO_INITDB_ROOT_USERNAME=${MONGO_INITDB_ROOT_USERNAME}
MONGO_INITDB_ROOT_PASSWORD=${MONGO_INITDB_ROOT_PASSWORD}
MONGO_INITDB_DATABASE=${MONGO_INITDB_DATABASE}
MONGO_URI=mongodb://admin:admin123@mongodb:27017/ontology?authSource=admin

# JWT Configuration
JWT_SECRET=${JWT_SECRET}
JWT_EXPIRATION=86400

# GraphDB Configuration
GRAPHDB_URL=http://graphdb:7200
GRAPHDB_REPOSITORY=ontocode
GRAPHDB_ADMIN_PASSWORD=${GRAPHDB_ADMIN_PASSWORD}

# Service URLs
AUTH_SERVICE_URL=http://ontocode-auth:8081
EDITOR_SERVICE_URL=http://ontocode-editor:8082
PLUGIN_SERVICE_URL=http://ontocode-plugin:8084
SWRL_SERVICE_URL=http://ontocode-swrl:8085

# Docker Registry
DOCKER_REGISTRY=${REGISTRY}
EOF

echo -e "${GREEN}[OK] Workspace ready${NC}"

# [3/6] Check/Pull images
echo ""
echo "[3/6] Checking images..."
if docker images ${REGISTRY}/ontocode-gateway:${VERSION} --format "{{.Repository}}" 2>/dev/null | grep -q "ontocode-gateway"; then
    echo "[INFO] Images already available"
    echo -e "${GREEN}[OK] Images ready${NC}"
else
    echo "[INFO] Pulling pre-built images from ${REGISTRY}..."
    echo "This may take a few minutes on first run..."
    echo ""
    
    for image in ontocode-graphdb ontocode-auth ontocode-gateway ontocode-editor ontocode-swrl ontocode-plugin ontocode-plugin-init ontocode-vscode-web; do
        echo -n "   Pulling ${REGISTRY}/${image}:${VERSION}..."
        if docker pull ${REGISTRY}/${image}:${VERSION} &> /dev/null; then
            echo -e " ${GREEN}[OK]${NC}"
        else
            echo -e " ${YELLOW}[WARN] Failed - will build locally${NC}"
        fi
    done
    echo ""
    echo -e "${GREEN}[OK] Images ready${NC}"
fi

# [4/6] Start services
echo ""
echo "[4/6] Checking and starting services..."
if docker compose ps --services --filter "status=running" 2>/dev/null | grep -q "ontology-gateway"; then
    echo "[INFO] Services are already running"
    echo -e "${GREEN}[OK] All services active${NC}"
else
    echo "[INFO] Starting services..."
    docker compose down &> /dev/null
    export DOCKER_REGISTRY=${REGISTRY}
    
    if docker compose up -d; then
        echo -e "${GREEN}[OK] All services started${NC}"
    else
        echo -e "${RED}[ERROR] Failed to start services. Check errors above.${NC}"
        read -p "Press Enter to exit..."
        exit 1
    fi
fi

# [5/6] Create desktop shortcut
echo ""
echo "[5/6] Creating desktop shortcut..."
SCRIPT_PATH="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

# Detect OS
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    DESKTOP="$HOME/Desktop"
    cat > "${DESKTOP}/OntoCode.command" << EOF
#!/bin/bash
cd "$(dirname "$0")"
"${SCRIPT_PATH}"
EOF
    chmod +x "${DESKTOP}/OntoCode.command"
    if [ -f "${DESKTOP}/OntoCode.command" ]; then
        echo -e "${GREEN}[OK] Desktop shortcut created${NC}"
    else
        echo -e "${YELLOW}[WARN] Could not create desktop shortcut${NC}"
    fi
else
    # Linux
    DESKTOP="$HOME/Desktop"
    if [ -d "$DESKTOP" ]; then
        cat > "${DESKTOP}/OntoCode.desktop" << EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=OntoCode
Comment=One-click launcher for OntoCode
Exec=${SCRIPT_PATH}
Icon=utilities-terminal
Terminal=true
Categories=Development;
EOF
        chmod +x "${DESKTOP}/OntoCode.desktop"
        if [ -f "${DESKTOP}/OntoCode.desktop" ]; then
            echo -e "${GREEN}[OK] Desktop shortcut created${NC}"
        else
            echo -e "${YELLOW}[WARN] Could not create desktop shortcut${NC}"
        fi
    else
        echo -e "${YELLOW}[WARN] Desktop folder not found${NC}"
    fi
fi

# [6/6] Wait and open
echo ""
echo "[6/6] Waiting for services to be ready..."
if docker compose ps --services --filter "status=running" 2>/dev/null | grep -q "ontology-gateway"; then
    sleep 5
else
    sleep 40
fi
echo -e "${GREEN}[OK] Services initialized${NC}"

# Display info
echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}   OntoCode is running!${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""
echo "   VS Code Web Editor:  http://localhost:3000"
echo "   API Gateway:         http://localhost:80"
echo "   GraphDB:             http://localhost:7200"
echo "   MongoDB:             mongodb://localhost:27017"
echo ""
echo "   Stop:  docker compose down"
echo "   Logs:  docker compose logs -f"
echo ""
echo -e "${CYAN}========================================${NC}"
echo "Opening VS Code Web Editor..."

# Open browser based on OS
sleep 3
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    open http://localhost:3000
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    # Linux
    if command -v xdg-open &> /dev/null; then
        xdg-open http://localhost:3000 &> /dev/null
    elif command -v gnome-open &> /dev/null; then
        gnome-open http://localhost:3000 &> /dev/null
    fi
fi

echo ""
read -p "Press Enter to exit..."
