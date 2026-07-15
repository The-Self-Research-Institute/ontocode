# OntoCode - Docker-Only Setup Guide

This guide is for users who want to run OntoCode using **only Docker**, without the source code.

## Prerequisites

- Docker Desktop installed and running
- At least 8GB RAM available
- Ports available: 80, 3000, 7200, 8083, 8084, 8086, 8087, 27017

## Quick Start (3 Steps)

### Step 1: Get the Required Files

You need only **2 files**:
1. `docker-compose.yml` - The main configuration file
2. `CORETOPIA_GRAPHDB_FREE_v11.1.license` - GraphDB license file (place in same directory)

### Step 2: Create Data Directory

```bash
# Windows PowerShell
mkdir -p data/projects

# Linux/Mac
mkdir -p data/projects
```

### Step 3: Start All Services

```bash
docker compose up -d
```

That's it! The system will:
- ✅ Pull all required Docker images
- ✅ Start MongoDB, GraphDB, and all services
- ✅ Automatically create "ontocode" repository with **inference DISABLED**
- ✅ Initialize plugins
- ✅ Start VS Code web editor on http://localhost:3000

## Service URLs

| Service | URL | Description |
|---------|-----|-------------|
| **VS Code Web** | http://localhost:3000 | Web-based ontology editor |
| **GraphDB** | http://localhost:7200 | RDF triple store interface |
| **API Gateway** | http://localhost:80 | Main API endpoint |
| **Auth Service** | http://localhost:8086 | Authentication |
| **Editor Service** | http://localhost:8083 | Ontology editing backend |
| **SWRL Service** | http://localhost:8084 | SWRL rules engine |
| **Plugin Service** | http://localhost:8087 | Plugin management |
| **MongoDB** | mongodb://localhost:27017 | Database |

## Default Credentials

- **MongoDB**: admin / changeme123
- **GraphDB**: No authentication by default

## Verify Installation

```bash
# Check all services are running
docker compose ps

# Expected output: 10 containers running
# - ontocode-mongo
# - ontocode-graphdb
# - ontocode-graphdb-init (exits after completion)
# - ontocode-auth
# - ontocode-gateway
# - ontocode-editor
# - ontocode-swrl
# - ontocode-plugin
# - ontocode-plugin-init (exits after completion)
# - ontocode-vscode-web
```

## GraphDB Repository Details

The system automatically creates a repository named **"ontocode"** with:
- **Repository ID**: ontocode
- **Inference**: DISABLED (ruleset: empty)
- **Status**: Active and accessible
- **Configuration**: Optimized for ontology editing without reasoning

You can verify by visiting: http://localhost:7200/repositories/ontocode

## Common Commands

```bash
# Start all services
docker compose up -d

# Stop all services
docker compose down

# View logs
docker compose logs -f

# View specific service logs
docker logs ontocode-vscode-web
docker logs ontocode-graphdb-init

# Restart specific service
docker compose restart vscode-web

# Rebuild and restart
docker compose up -d --build
```

## Troubleshooting

### GraphDB Repository Not Created

Check initialization logs:
```bash
docker logs ontocode-graphdb-init
```

Expected output:
```
=== GraphDB Repository Init ===
[OK] Repository ontocode already exists
Setting ontocode as active repository...
[OK] Repository ontocode is ACTIVE and accessible
[OK] Active Repository: ontocode
[OK] Inference: DISABLED (ruleset: empty)
=== Init Complete ===
```

### Services Not Starting

```bash
# Check Docker resources
docker system df

# Increase Docker Desktop memory to 8GB minimum
# Docker Desktop → Settings → Resources → Memory

# Restart Docker Desktop
```

### Port Conflicts

If ports are already in use, edit `docker-compose.yml` and change port mappings:
```yaml
ports:
  - "3001:3000"  # Change left side only
```

## Data Persistence

Your data is stored in Docker volumes:
- `mongo-data` - MongoDB database
- `graphdb-data` - GraphDB repository data
- `./data/projects` - Your ontology files (mapped to host)

To backup:
```bash
# Backup volumes
docker compose down
docker run --rm -v ontocode_mongo-data:/data -v ${PWD}/backup:/backup alpine tar czf /backup/mongo-backup.tar.gz /data
docker run --rm -v ontocode_graphdb-data:/data -v ${PWD}/backup:/backup alpine tar czf /backup/graphdb-backup.tar.gz /data
```

## Environment Variables (Optional)

Create `.env` file in the same directory as `docker-compose.yml`:

```env
# MongoDB
MONGODB_DATABASE=ontocode
MONGO_ROOT_USER=admin
MONGO_ROOT_PASSWORD=changeme123

# GraphDB
GRAPHDB_REPOSITORY=ontocode

# JWT Secret
JWT_SECRET=your-secret-key-here

# Logging
LOGGING_LEVEL_ROOT=INFO

# Workspace Path
WORKSPACE_PATH=./data/projects
```

## Next Steps

1. **Open VS Code Web**: http://localhost:3000
2. **Create an ontology project** in `/workspace/projects`
3. **Access GraphDB interface**: http://localhost:7200
4. **Start editing OWL files** with the OntoCode extension

## System Architecture

```
┌─────────────────┐
│  VS Code Web    │  Port 3000 (Your Interface)
│   (Browser)     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  API Gateway    │  Port 80
└────────┬────────┘
         │
         ├──────────┬──────────┬──────────┬──────────┐
         ▼          ▼          ▼          ▼          ▼
    ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐
    │ Auth │  │Editor│  │ SWRL │  │Plugin│  │GraphDB│
    │ 8086 │  │ 8083 │  │ 8084 │  │ 8087 │  │ 7200 │
    └──────┘  └───┬──┘  └───┬──┘  └───┬──┘  └──────┘
                  │         │         │
                  └─────────┴─────────┘
                          │
                          ▼
                    ┌──────────┐
                    │ MongoDB  │
                    │  27017   │
                    └──────────┘
```

## Support

- Check logs: `docker compose logs -f`
- Restart services: `docker compose restart`
- Full reset: `docker compose down -v` (WARNING: Deletes all data)

---

**Important**: The GraphDB repository "ontocode" is automatically created with **inference disabled** for better performance during ontology editing. All services are pre-configured to use this repository.
