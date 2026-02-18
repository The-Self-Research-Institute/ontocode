#!/bin/bash
# Build multi-platform Docker images and push to registry
# Supports both Intel (amd64) and Apple Silicon (arm64) Macs
# Usage: ./build-and-push.sh [registry] [version]

REGISTRY=${1:-ghcr.io/yourusername}
VERSION=${2:-latest}

echo "============================================"
echo "   Building Multi-Platform OntoCode Images"
echo "   Registry: $REGISTRY"
echo "   Version: $VERSION"
echo "   Platforms: linux/amd64, linux/arm64"
echo "============================================"
echo

# Setup buildx for multi-platform builds
echo "Setting up buildx builder..."
docker buildx create --name ontocode-builder --use --driver docker-container 2>/dev/null || docker buildx use ontocode-builder
docker buildx inspect --bootstrap

echo
echo "Building and pushing multi-platform images..."
echo "This may take a while as images are built for both Intel and ARM architectures..."
echo

# Build and push all images with multi-platform support
docker buildx build --platform linux/amd64,linux/arm64 -t $REGISTRY/ontocode-graphdb:$VERSION -f Dockerfile.graphdb --push .
docker buildx build --platform linux/amd64,linux/arm64 -t $REGISTRY/ontocode-auth:$VERSION -f Dockerfile.auth --push .
docker buildx build --platform linux/amd64,linux/arm64 -t $REGISTRY/ontocode-gateway:$VERSION -f Dockerfile.gateway --push .
docker buildx build --platform linux/amd64,linux/arm64 -t $REGISTRY/ontocode-editor:$VERSION -f Dockerfile.editor --push .
docker buildx build --platform linux/amd64,linux/arm64 -t $REGISTRY/ontocode-swrl:$VERSION -f Dockerfile.swrl --push .
docker buildx build --platform linux/amd64,linux/arm64 -t $REGISTRY/ontocode-plugin:$VERSION -f Dockerfile.plugin --push .
docker buildx build --platform linux/amd64,linux/arm64 -t $REGISTRY/ontocode-plugin-init:$VERSION -f Dockerfile.plugin-init --push .
docker buildx build --platform linux/amd64,linux/arm64 -t $REGISTRY/ontocode-vscode-web:$VERSION -f Dockerfile.vscode-extension --push .

echo
echo "Cleaning up buildx builder..."
docker buildx rm ontocode-builder 2>/dev/null

echo
echo "============================================"
echo "   SUCCESS! All images built and pushed!"
echo "============================================"
echo
echo "Multi-platform support:"
echo "  ✓ Intel/AMD (linux/amd64)"
echo "  ✓ Apple Silicon M1/M2/M3 (linux/arm64)"
echo
echo "Users can now run on any platform:"
echo "  docker compose up -d"
echo
echo "Make sure to set DOCKER_REGISTRY in .env file:"
echo "  DOCKER_REGISTRY=$REGISTRY"
