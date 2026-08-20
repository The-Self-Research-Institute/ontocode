#!/bin/bash
set -e

echo "🚀 Setting up Ontology Platform"

command -v java >/dev/null 2>&1 || { echo "❌ Java required"; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo "❌ Maven required"; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "❌ Docker required"; exit 1; }

echo "✅ Prerequisites OK"

echo "📦 Building shared modules..."
cd shared/common-models && mvn clean install -DskipTests
cd ../common-utils && mvn clean install -DskipTests
cd ../..

echo "🔨 Building all services..."
mvn clean package -DskipTests

echo "🐳 Starting MongoDB..."
docker-compose up -d mongodb

echo ""
echo "✅ Setup complete!"
echo "Run: ./scripts/start-services.sh"