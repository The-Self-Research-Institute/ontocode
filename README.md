# Ontology Platform

Microservices-based ontology editing platform.

## Services

- **Gateway** (8082): API Gateway
- **Auth** (8083): Auth
- **OWL Editor** (8084): Core ontology operations
- **SWRL Service** (8085): SWRL rules engine
- **VS Code Extension**: Desktop editor

## Quick Start
```bash
# Setup
./scripts/setup.sh

# Start services
./scripts/start-services.sh

# Or use Docker
docker-compose up
```

## Structure
```
ontology-platform/
├── ontology-gateway/    # API Gateway
├── ontology-auth/       # AUTH
├── ontology-editor/     # OWL editing
├── ontology-swrl-service/   # SWRL rules
├── shared/               # Shared libs
│   ├── common-models/
│   └── common-utils/
└── ontology-vscode-extension/     # VS Code extension
```

## Build
```bash
# Build all
mvn clean install

# Build specific service
cd gateway
mvn clean package
```