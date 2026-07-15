# OntoCode Architecture Diagrams

## Docker-Only Mode Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         User's Browser                          │
│                   http://localhost:3000                         │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                      Docker Network                              │
│                                                                  │
│  ┌──────────────────┐         ┌──────────────────┐             │
│  │  VS Code Web     │◄────────┤   API Gateway    │             │
│  │  Container       │         │   (Port 80)      │             │
│  │  (Port 3000)     │         └─────────┬────────┘             │
│  │                  │                   │                       │
│  │ - Extension Code │                   │                       │
│  │ - Web Server     │         ┌─────────┴────────┐             │
│  │ - VS Code UI     │         │                  │             │
│  └──────────────────┘         ↓                  ↓             │
│                       ┌────────────┐    ┌────────────┐         │
│  ┌──────────────┐    │ OWL Editor │    │    Auth    │         │
│  │   GraphDB    │◄───┤  (8083)    │    │   (8086)   │         │
│  │   (7200)     │    └────────────┘    └────────────┘         │
│  │              │                                               │
│  │ - RDF Store  │    ┌────────────┐    ┌────────────┐         │
│  └──────────────┘    │    SWRL    │    │  Plugins   │         │
│                      │   (8084)   │    │   (8087)   │         │
│  ┌──────────────┐    └────────────┘    └────────────┘         │
│  │   MongoDB    │                                               │
│  │   (27017)    │                                               │
│  │              │                                               │
│  │ - Metadata   │                                               │
│  │ - Users      │                                               │
│  │ - Projects   │                                               │
│  └──────────────┘                                               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

✅ EVERYTHING in Docker
✅ NO Node.js required
✅ ONE command: docker-install.bat
```

## Hybrid Mode Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         User's Browser                          │
│                   http://localhost:8000                         │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                      Local Machine                               │
│                                                                  │
│  ┌──────────────────┐         ┌──────────────────┐             │
│  │ VS Code Web      │         │   Node.js        │             │
│  │ (Local Process)  │◄────────┤   Dev Server     │             │
│  │                  │         │                  │             │
│  │ - Hot Reload ✨  │         │ - npm run watch  │             │
│  │ - Debug Mode 🐛  │         │ - Live Rebuild   │             │
│  │ - Source Access  │         └──────────────────┘             │
│  └──────────────────┘                                           │
│         │                                                        │
└─────────┼────────────────────────────────────────────────────────┘
          │
          ↓
┌─────────────────────────────────────────────────────────────────┐
│                      Docker Network                              │
│                                                                  │
│  ┌──────────────────┐         ┌──────────────────┐             │
│  │   API Gateway    │         │    Auth          │             │
│  │   (Port 80)      │         │   (8086)         │             │
│  └─────────┬────────┘         └────────────┬─────┘             │
│            │                               │                    │
│            ↓                               ↓                    │
│   ┌────────────┐    ┌────────────┐    ┌────────────┐          │
│   │ OWL Editor │    │    SWRL    │    │  Plugins   │          │
│   │  (8083)    │    │   (8084)   │    │   (8087)   │          │
│   └─────┬──────┘    └────────────┘    └────────────┘          │
│         │                                                        │
│         ↓                                                        │
│  ┌──────────────┐    ┌──────────────┐                          │
│  │   GraphDB    │    │   MongoDB    │                          │
│  │   (7200)     │    │   (27017)    │                          │
│  └──────────────┘    └──────────────┘                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

✅ Backend in Docker
✅ Frontend local with hot-reload
✅ ONE command: install-and-run.bat
```

## Installation Flow

### Docker-Only Installation Flow

```
Start
  │
  ├─► Check Docker ✓
  │
  ├─► Clean existing containers
  │
  ├─► Build Docker images
  │   ├── Base images (Node, MongoDB, etc.)
  │   ├── Backend services
  │   └── VS Code Web container ⭐
  │
  ├─► Start all containers
  │   ├── Database layer (Mongo, GraphDB)
  │   ├── Service layer (Auth, Gateway, Editor, SWRL, Plugins)
  │   └── VS Code Web ⭐
  │
  ├─► Wait for health checks
  │
  └─► Open browser → http://localhost:3000
  
Total Time: 8-12 minutes (first run)
           1-2 minutes (subsequent)
```

### Hybrid Installation Flow

```
Start
  │
  ├─► Check Docker ✓
  │
  ├─► Check Node.js ✓
  │
  ├─► Clean existing containers
  │
  ├─► Build & start Docker backend
  │   ├── Database layer
  │   └── Service layer
  │
  ├─► Install extension dependencies (npm install)
  │
  ├─► Build extension bundle (npm run bundle:web)
  │
  ├─► Start VS Code dev server (npm run test-web)
  │
  └─► Open browser → auto-opens
  
Total Time: 10-15 minutes (first run)
           30-60 seconds (subsequent)
```

## Auto-Detection Logic

```
install-and-run.bat
       │
       ├─► Is Node.js installed?
       │
       ├─── YES ───► Hybrid Mode
       │             - Use local Node.js
       │             - Hot reload enabled
       │             - Developer workflow
       │
       └─── NO ────► Docker-Only Mode
                     - Everything in Docker
                     - No Node.js needed
                     - End-user workflow
```

## Service Communication

### Docker-Only Mode

```
VS Code Web Container
    │
    ├─► API Gateway (http://gateway:80)
    │      │
    │      ├─► Auth Service (http://auth:8086)
    │      │      └─► MongoDB
    │      │
    │      ├─► OWL Editor (http://owl-editor:8083)
    │      │      ├─► MongoDB (metadata)
    │      │      └─► GraphDB (triples)
    │      │
    │      ├─► SWRL Service (http://swrl-service:8084)
    │      │      └─► GraphDB
    │      │
    │      └─► Plugin Service (http://plugin-service:8087)
    │             └─► MongoDB
    │
    └─► Direct connections using Docker network
        (Services use container names as hostnames)
```

### Hybrid Mode

```
Local VS Code Web
    │
    ├─► API Gateway (http://localhost:80)
    │      │
    │      └─► Same as above...
    │
    └─► Uses localhost:PORT for all services
        (Ports are exposed to host machine)
```

## Data Persistence

```
Docker Volumes:
    │
    ├─► mongo-data
    │   └─► Stores: Users, Projects, Metadata, Plugins
    │
    └─► graphdb-data
        └─► Stores: RDF Triples, Ontologies

Local Volumes:
    │
    └─► ./data/projects
        └─► Mounted into VS Code Web for file access
            (Works in both modes)
```

## Network Topology

```
┌─────────────────────────────────────────────┐
│         ontology-net (Bridge Network)        │
│                                              │
│  All containers connected to this network   │
│  Each container can reach others by name    │
│                                              │
│  Examples:                                   │
│  - mongo:27017                              │
│  - graphdb:7200                             │
│  - gateway:80                               │
│  - auth:8086                                │
│                                              │
│  Port mappings to host:                     │
│  - 3000:3000 → vscode-web                   │
│  - 80:80 → gateway                          │
│  - 8086:8086 → auth                         │
│  - 8083:8083 → owl-editor                   │
│  - 8084:8084 → swrl-service                 │
│  - 8087:8087 → plugin-service               │
│  - 7200:7200 → graphdb                      │
│  - 27017:27017 → mongo                      │
│                                              │
└─────────────────────────────────────────────┘
```

## Build Process

### Multi-Stage Docker Build (VS Code Extension)

```
Stage 1: Builder
    │
    ├─► FROM node:21-alpine
    │
    ├─► Copy package.json
    │
    ├─► npm ci (install dependencies)
    │
    ├─► Copy source code
    │
    ├─► npm run compile (TypeScript → JavaScript)
    │
    └─► npm run bundle:web (Webpack build)
        │
        └─► Output: /app/dist/web/extension.js

Stage 2: Runtime
    │
    ├─► FROM node:21-alpine
    │
    ├─► npm install -g @vscode/test-web
    │
    ├─► COPY --from=builder /app
    │
    └─► CMD vscode-test-web
        └─► Serves extension on port 3000
```

---

## Summary

### Docker-Only Mode
- 🐳 8 containers (including VS Code Web)
- ✅ Zero local dependencies (except Docker)
- 🌐 Access: http://localhost:3000
- 👥 Perfect for end users

### Hybrid Mode  
- 🐳 7 containers (backend only)
- 💻 VS Code extension runs locally
- ✨ Hot-reload for development
- 👨‍💻 Perfect for developers

### Both Modes Share
- 📦 Same backend services
- 🗄️ Same data storage
- 🔗 Same API endpoints
- 🎯 One-command installation
