# 🚀 OntoCode Quick Start Guide

Get OntoCode up and running in minutes with our one-click installation!

## Prerequisites

Before you begin, ensure you have:

1. **Docker Desktop** installed and running
   - Download from: https://www.docker.com/products/docker-desktop
   - Make sure Docker Desktop is started before running the installation

2. **Node.js** (v18 or higher)
   - Download from: https://nodejs.org/
   - Verify installation: `node --version`

## One-Click Installation

### Windows (Batch Script)

Simply double-click or run:

```cmd
install-and-run.bat
```

### Windows (PowerShell)

For more control and better output:

```powershell
.\install-and-run.ps1
```

**PowerShell Options:**
- Skip browser auto-launch: `.\install-and-run.ps1 -SkipBrowser`
- Skip Docker rebuild: `.\install-and-run.ps1 -NoBuild`

### What Happens During Installation?

The installation script will:

1. ✅ Check that Docker and Node.js are installed
2. 🧹 Clean up any existing containers
3. 🏗️ Build all Docker services (first run takes ~5-10 minutes)
4. 🚀 Start all backend services:
   - MongoDB (Database)
   - GraphDB (RDF Triple Store)
   - Auth Service
   - API Gateway
   - OWL Editor
   - SWRL Service
   - Plugin Service
5. 📦 Install and build the VS Code extension
6. 🌐 Launch the web-based VS Code editor in your browser

## After Installation

Once installation completes, you'll have access to:

| Service | URL | Description |
|---------|-----|-------------|
| **VS Code Web Editor** | Opens automatically | Web-based ontology editor |
| **API Gateway** | http://localhost:80 | Main API endpoint |
| **Auth Service** | http://localhost:8086 | Authentication API |
| **OWL Editor** | http://localhost:8083 | Ontology editing API |
| **SWRL Service** | http://localhost:8084 | SWRL rules engine |
| **Plugin Service** | http://localhost:8087 | Plugin management |
| **GraphDB Console** | http://localhost:7200 | RDF database UI |
| **MongoDB** | mongodb://localhost:27017 | Document database |

## First Time Login

1. When the VS Code web editor opens, you'll see the OntoCode extension
2. Use the command palette (`Ctrl+Shift+P` or `Cmd+Shift+P`)
3. Type "OntoCode: Edit" to start editing OWL files
4. You may be prompted to register/login on first use

## Stopping the Platform

To stop all services:

```cmd
docker compose down
```

To stop and remove all data (fresh start):

```cmd
docker compose down -v
```

## Restarting the Platform

After initial installation, you can quickly restart:

### Just Start Services (No Rebuild)

```powershell
.\install-and-run.ps1 -NoBuild
```

Or manually:

```cmd
docker compose up -d
cd ontology-vscode-extension
npm run test-web
```

## Troubleshooting

### Docker Not Running

**Error:** "Docker is not running"

**Solution:** Start Docker Desktop and wait for it to fully initialize, then run the script again.

### Port Already in Use

**Error:** "Bind for 0.0.0.0:XXXX failed: port is already allocated"

**Solution:** 
```cmd
docker compose down
netstat -ano | findstr :XXXX
taskkill /PID <PID> /F
```

### Services Not Starting

**Error:** Services are unhealthy or failing

**Solution:**
1. Check Docker Desktop has enough resources (4GB RAM minimum)
2. View logs: `docker compose logs <service-name>`
3. Try a clean restart: `docker compose down -v && install-and-run.bat`

### Extension Not Loading

**Error:** Extension fails to load in browser

**Solution:**
```cmd
cd ontology-vscode-extension
npm clean-install
npm run bundle:web
npm run test-web
```

### First Build is Slow

**This is normal!** The first time you run the installation:
- Docker images need to be downloaded
- Maven dependencies need to be downloaded
- Services need to compile from source

Subsequent runs will be much faster (30-60 seconds).

## Manual Installation (Alternative)

If you prefer manual control, see [README.md](README.md) for detailed setup instructions.

## Next Steps

- 📖 Read the [README.md](README.md) for detailed documentation
- 🔧 Check [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for common issues
- 🧪 See [TESTING_GUIDE.md](TESTING_GUIDE.md) for testing instructions
- 📝 Review [PLUGIN_QUICK_REFERENCE.md](PLUGIN_QUICK_REFERENCE.md) for plugin development

## System Status Check

Want to quickly check if everything is running?

**Windows:**
```cmd
check-status.bat
```

**PowerShell:**
```powershell
.\check-status.ps1
```

This will show you:
- ✅ Docker and Node.js installation status
- 📊 All running containers and their status
- 🌐 Service URLs for easy access

## Need Help?

- **Check system status**: `check-status.bat` or `.\check-status.ps1`
- **View logs**: `docker compose logs -f <service-name>`
- **View all containers**: `docker compose ps`
- **Restart a service**: `docker compose restart <service-name>`
- **View real-time logs**: `docker compose logs -f`

## Create Desktop Shortcut (Windows)

For ultimate convenience, create a desktop shortcut:

```cmd
create-desktop-shortcut.bat
```

Double-click the shortcut anytime to launch OntoCode!

---

**Enjoy building ontologies with OntoCode! 🧩**
