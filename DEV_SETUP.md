# OntoCode Local Development Setup

This guide helps you build and run all OntoCode services from local source code.

## Files Overview

- **docker-compose.dev.yml** - Development compose file that builds all services locally
- **docker-compose.yml** - Production compose file that pulls pre-built images
- **build-dev.bat** - Build all services locally
- **start-dev.bat** - Start all development services
- **stop-dev.bat** - Stop all development services

## Quick Start

### 1. Build All Services

```bash
# Windows
.\build-dev.bat

# Linux/Mac or manual
docker compose -f docker-compose.dev.yml build
```

**Note:** First build takes 10-15 minutes as it compiles Java services and builds Node.js apps.

### 2. Start All Services

```bash
# Windows
.\start-dev.bat

# Linux/Mac or manual
docker compose -f docker-compose.dev.yml up -d
```

### 3. Access Services

Once started, services are available at:

| Service | URL | Description |
|---------|-----|-------------|
| Gateway | http://localhost:80 | API Gateway (entry point) |
| Auth Service | http://localhost:8086 | Authentication/Authorization |
| OWL Editor | http://localhost:8083 | Ontology editing service |
| SWRL Service | http://localhost:8084 | SWRL rules processing |
| Plugin Service | http://localhost:8087 | Plugin management |
| GraphDB | http://localhost:7200 | RDF Triple Store |
| MongoDB | mongodb://localhost:27017 | Database |
| VS Code Web | http://localhost:3000 | Web-based VS Code |

### 4. Stop Services

```bash
# Windows
.\stop-dev.bat

# Linux/Mac or manual
docker compose -f docker-compose.dev.yml down
```

## Development Workflow

### Rebuild After Code Changes

```bash
# Rebuild specific service
docker compose -f docker-compose.dev.yml build owl-editor

# Rebuild and restart specific service
docker compose -f docker-compose.dev.yml up -d --build owl-editor

# Rebuild all services
docker compose -f docker-compose.dev.yml build

# Rebuild all and restart
docker compose -f docker-compose.dev.yml up -d --build
```

### View Logs

```bash
# All services
docker compose -f docker-compose.dev.yml logs -f

# Specific service
docker compose -f docker-compose.dev.yml logs -f owl-editor

# Last 100 lines
docker compose -f docker-compose.dev.yml logs --tail=100 -f
```

### Check Service Status

```bash
docker compose -f docker-compose.dev.yml ps
```

### Restart Services

```bash
# Restart all
docker compose -f docker-compose.dev.yml restart

# Restart specific service
docker compose -f docker-compose.dev.yml restart owl-editor
```

## Individual Service Builds

You can build services individually for faster iteration:

```bash
# Build only OWL Editor
docker compose -f docker-compose.dev.yml build owl-editor

# Build only Auth Service
docker compose -f docker-compose.dev.yml build auth

# Build only Gateway
docker compose -f docker-compose.dev.yml build gateway

# Build only VS Code Extension
docker compose -f docker-compose.dev.yml build vscode-web
```

## Troubleshooting

### Build Fails

1. **Clean Docker cache:**
   ```bash
   docker compose -f docker-compose.dev.yml build --no-cache
   ```

2. **Check disk space:**
   ```bash
   docker system df
   ```

3. **Prune unused images:**
   ```bash
   docker image prune -a
   ```

### Service Won't Start

1. **Check logs:**
   ```bash
   docker compose -f docker-compose.dev.yml logs <service-name>
   ```

2. **Verify dependencies:**
   - MongoDB must be healthy before app services start
   - GraphDB must be healthy before owl-editor starts
   - Auth must be healthy before gateway starts

3. **Check port conflicts:**
   ```bash
   netstat -ano | findstr "80\|8083\|8084\|8086\|8087\|7200\|27017\|3000"
   ```

### Reset Everything

```bash
# Stop services and remove volumes (deletes all data!)
docker compose -f docker-compose.dev.yml down -v

# Remove local images
docker images | grep "ontocode" | awk '{print $3}' | xargs docker rmi -f

# Rebuild from scratch
docker compose -f docker-compose.dev.yml build --no-cache
docker compose -f docker-compose.dev.yml up -d
```

## Environment Variables

Create a `.env` file in the root directory to customize:

```env
# MongoDB
MONGO_ROOT_USER=admin
MONGO_ROOT_PASSWORD=changeme123
MONGODB_DATABASE=ontocode

# GraphDB
GRAPHDB_REPOSITORY=ontocode

# JWT Secret (base64 encoded)
JWT_SECRET=b250b2NvZGUtc3VwZXItc2VjcmV0LWp3dC1rZXktMjAyNC1taW5pbXVtLTI1Ni1iaXRzLXJlcXVpcmVkIQ==

# Logging
LOGGING_LEVEL_ROOT=INFO

# Workspace path for VS Code
WORKSPACE_PATH=./data/projects
```

## Differences from Production

| Aspect | Development | Production |
|--------|-------------|------------|
| Images | Built locally | Pulled from registry |
| Image Tags | `ontocode-*:local` | `sindhujacoretopia/ontocode-*:latest` |
| Build Time | 10-15 minutes | 1-2 minutes (pull only) |
| Updates | Manual rebuild | Pull latest image |
| Source Code | Local changes included | Fixed release version |

## Production Deployment

For production, use the standard compose file:

```bash
docker compose up -d
```

This pulls pre-built, tested images from the registry instead of building locally.

## Additional Commands

### Execute commands in containers

```bash
# Access MongoDB shell
docker exec -it ontocode-mongo mongosh -u admin -p changeme123

# Access container shell
docker exec -it ontocode-editor /bin/bash

# Check Java logs
docker exec -it ontocode-editor cat /var/log/app.log
```

### Monitor Resource Usage

```bash
docker stats
```

### Network Debugging

```bash
# Inspect network
docker network inspect ontocode_ontology-net

# Test connectivity between services
docker exec -it ontocode-gateway curl http://auth:8086/actuator/health
```

## Getting Help

- Check service logs for errors
- Verify all dependencies are healthy
- Ensure ports are not in use
- Check Docker daemon is running
- Verify sufficient disk space (requires ~5GB for all images)

## Service Dependencies

```
mongo (base)
  ├─> auth
  │    └─> gateway
  └─> plugin-service
       └─> plugin-init

graphdb (base)
  └─> owl-editor
       └─> swrl-service

gateway
  └─> vscode-web
```
