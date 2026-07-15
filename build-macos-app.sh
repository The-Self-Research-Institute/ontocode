#!/bin/bash
# ========================================
# Create OntoCode.app for macOS
# ========================================

echo "========================================"
echo "  Building OntoCode.app for macOS"
echo "========================================"
echo ""

APP_NAME="OntoCode.app"
BUNDLE_ID="com.coretopia.ontocode"
APP_VERSION="1.0.0"

# Create app bundle structure
echo "Creating app bundle structure..."
mkdir -p "${APP_NAME}/Contents/MacOS"
mkdir -p "${APP_NAME}/Contents/Resources"

# Create Info.plist
echo "Creating Info.plist..."
cat > "${APP_NAME}/Contents/Info.plist" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleExecutable</key>
    <string>launcher</string>
    <key>CFBundleIdentifier</key>
    <string>com.coretopia.ontocode</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>OntoCode</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0.0</string>
    <key>CFBundleVersion</key>
    <string>1</string>
    <key>LSMinimumSystemVersion</key>
    <string>10.13</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>LSApplicationCategoryType</key>
    <string>public.app-category.developer-tools</string>
</dict>
</plist>
EOF

# Create launcher script
echo "Creating launcher script..."
cat > "${APP_NAME}/Contents/MacOS/launcher" << 'EOFSCRIPT'
#!/bin/bash
# OntoCode Launcher Script

REGISTRY="sindhujacoretopia"
VERSION="latest"

# Configuration
MONGO_INITDB_ROOT_USERNAME="admin"
MONGO_INITDB_ROOT_PASSWORD="admin123"
MONGO_INITDB_DATABASE="ontology"
JWT_SECRET="your-secret-key-change-this-in-production"
GRAPHDB_ADMIN_PASSWORD="admin"

# Open Terminal and run
osascript <<APPLESCRIPT
tell application "Terminal"
    activate
    do script "
    clear
    echo ''
    echo '\033[0;36m========================================\033[0m'
    echo '\033[0;36m   OntoCode One-Click Installation\033[0m'
    echo '\033[0;36m   Registry: ${REGISTRY}\033[0m'
    echo '\033[0;36m========================================\033[0m'
    echo ''
    
    # [1/6] Check Docker
    echo '[1/6] Checking Docker...'
    if ! command -v docker &> /dev/null; then
        echo '\033[0;31m[ERROR] Docker is not installed.\033[0m'
        echo 'Install from: https://www.docker.com/products/docker-desktop'
        read -p 'Press Enter to exit...'
        exit 1
    fi
    
    if ! docker ps &> /dev/null; then
        echo '\033[0;31m[ERROR] Docker is not running. Please start Docker.\033[0m'
        read -p 'Press Enter to exit...'
        exit 1
    fi
    echo '\033[0;32m[OK] Docker is running\033[0m'
    
    # [2/6] Prepare workspace
    echo ''
    echo '[2/6] Preparing workspace...'
    cd ~
    if [ ! -d 'OntoCode' ]; then
        mkdir -p OntoCode
    fi
    cd OntoCode
    mkdir -p data/projects
    
    # Create .env file
    echo '[INFO] Creating configuration...'
    cat > .env << 'ENVEOF'
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
GRAPHDB_REPOSITORY=ontology
GRAPHDB_ADMIN_PASSWORD=${GRAPHDB_ADMIN_PASSWORD}

# Service URLs
AUTH_SERVICE_URL=http://ontology-auth:8081
EDITOR_SERVICE_URL=http://ontology-editor:8082
PLUGIN_SERVICE_URL=http://ontology-plugin:8084
SWRL_SERVICE_URL=http://ontology-swrl:8085

# Docker Registry
DOCKER_REGISTRY=${REGISTRY}
ENVEOF
    
    echo '\033[0;32m[OK] Workspace ready\033[0m'
    
    # [3/6] Check/Pull images
    echo ''
    echo '[3/6] Checking images...'
    if docker images ${REGISTRY}/ontocode-gateway:${VERSION} --format '{{.Repository}}' 2>/dev/null | grep -q 'ontocode-gateway'; then
        echo '[INFO] Images already available'
        echo '\033[0;32m[OK] Images ready\033[0m'
    else
        echo '[INFO] Pulling pre-built images from ${REGISTRY}...'
        echo 'This may take a few minutes on first run...'
        echo ''
        
        for image in ontocode-graphdb ontocode-auth ontocode-gateway ontocode-editor ontocode-swrl ontocode-plugin ontocode-plugin-init ontocode-vscode-web; do
            echo -n \"   Pulling ${REGISTRY}/\${image}:${VERSION}...\"
            if docker pull ${REGISTRY}/\${image}:${VERSION} &> /dev/null; then
                echo ' \033[0;32m[OK]\033[0m'
            else
                echo ' \033[1;33m[WARN] Failed - will build locally\033[0m'
            fi
        done
        echo ''
        echo '\033[0;32m[OK] Images ready\033[0m'
    fi
    
    # [4/6] Start services
    echo ''
    echo '[4/6] Checking and starting services...'
    if docker compose ps --services --filter 'status=running' 2>/dev/null | grep -q 'ontology-gateway'; then
        echo '[INFO] Services are already running'
        echo '\033[0;32m[OK] All services active\033[0m'
    else
        echo '[INFO] Starting services...'
        docker compose down &> /dev/null
        export DOCKER_REGISTRY=${REGISTRY}
        
        if docker compose up -d; then
            echo '\033[0;32m[OK] All services started\033[0m'
        else
            echo '\033[0;31m[ERROR] Failed to start services.\033[0m'
            read -p 'Press Enter to exit...'
            exit 1
        fi
    fi
    
    # [5/6] Wait
    echo ''
    echo '[5/6] Waiting for services to be ready...'
    if docker compose ps --services --filter 'status=running' 2>/dev/null | grep -q 'ontology-gateway'; then
        sleep 5
    else
        sleep 40
    fi
    echo '\033[0;32m[OK] Services initialized\033[0m'
    
    # Display info
    echo ''
    echo '\033[0;36m========================================\033[0m'
    echo '\033[0;36m   OntoCode is running!\033[0m'
    echo '\033[0;36m========================================\033[0m'
    echo ''
    echo '   VS Code Web Editor:  http://localhost:3000'
    echo '   API Gateway:         http://localhost:80'
    echo '   GraphDB:             http://localhost:7200'
    echo '   MongoDB:             mongodb://localhost:27017'
    echo ''
    echo '   Stop:  docker compose down'
    echo '   Logs:  docker compose logs -f'
    echo ''
    echo '\033[0;36m========================================\033[0m'
    echo 'Opening VS Code Web Editor...'
    
    sleep 3
    open http://localhost:3000
    
    echo ''
    read -p 'Press Enter to exit...'
    "
end tell
APPLESCRIPT
EOFSCRIPT

# Make launcher executable
chmod +x "${APP_NAME}/Contents/MacOS/launcher"

# Create a simple icon (optional - using emoji as placeholder)
echo "Creating app icon..."
cat > "${APP_NAME}/Contents/Resources/appicon.icns" << 'EOF'
# Placeholder - in production, use a real .icns file
EOF

echo ""
echo "========================================"
echo "  Success!"
echo "========================================"
echo ""
echo "${APP_NAME} has been created!"
echo ""
echo "To install:"
echo "  1. Copy ${APP_NAME} to /Applications"
echo "  2. Or double-click to run from current location"
echo ""
echo "To share:"
echo "  1. Compress: zip -r OntoCode.zip ${APP_NAME}"
echo "  2. Share the .zip file"
echo "  3. Users extract and copy to Applications"
echo ""
echo "The .app will automatically:"
echo "  - Open Terminal"
echo "  - Check Docker status"
echo "  - Pull/start services"
echo "  - Create workspace in ~/OntoCode"
echo "  - Open http://localhost:3000"
echo ""

# Ask if user wants to copy to Applications
read -p "Copy to /Applications now? (y/n): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    cp -r "${APP_NAME}" /Applications/
    echo "Copied to /Applications/"
    echo "You can now launch OntoCode from Launchpad or Spotlight!"
fi

echo ""
echo "Done!"
