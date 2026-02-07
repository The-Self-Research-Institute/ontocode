#!/bin/bash
# Build all Docker images and push to registry
# Usage: ./build-and-push.sh [registry] [version]

REGISTRY=${1:-ghcr.io/yourusername}
VERSION=${2:-latest}

echo "============================================"
echo "   Building and Pushing OntoCode Images"
echo "   Registry: $REGISTRY"
echo "   Version: $VERSION"
echo "============================================"
echo

# Build all images
echo "Building images..."
docker build -t $REGISTRY/ontocode-graphdb:$VERSION -f Dockerfile.graphdb .
docker build -t $REGISTRY/ontocode-auth:$VERSION -f Dockerfile.auth .
docker build -t $REGISTRY/ontocode-gateway:$VERSION -f Dockerfile.gateway .
docker build -t $REGISTRY/ontocode-editor:$VERSION -f Dockerfile.editor .
docker build -t $REGISTRY/ontocode-swrl:$VERSION -f Dockerfile.swrl .
docker build -t $REGISTRY/ontocode-plugin:$VERSION -f Dockerfile.plugin .
docker build -t $REGISTRY/ontocode-plugin-init:$VERSION -f Dockerfile.plugin-init .
docker build -t $REGISTRY/ontocode-vscode-web:$VERSION -f Dockerfile.vscode-extension .

echo
echo "============================================"
echo "   Pushing images to registry..."
echo "============================================"
echo

# Push all images
docker push $REGISTRY/ontocode-graphdb:$VERSION
docker push $REGISTRY/ontocode-auth:$VERSION
docker push $REGISTRY/ontocode-gateway:$VERSION
docker push $REGISTRY/ontocode-editor:$VERSION
docker push $REGISTRY/ontocode-swrl:$VERSION
docker push $REGISTRY/ontocode-plugin:$VERSION
docker push $REGISTRY/ontocode-plugin-init:$VERSION
docker push $REGISTRY/ontocode-vscode-web:$VERSION

echo
echo "============================================"
echo "   All images built and pushed successfully!"
echo "============================================"
echo
echo "Users can now run: docker compose up -d"
echo "Make sure to set DOCKER_REGISTRY in .env file"
