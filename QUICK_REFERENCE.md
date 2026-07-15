# 🎯 OntoCode - Installation Quick Reference

## Which Script Should I Use?

```
┌─────────────────────────────────────────────────────────┐
│                                                          │
│  Do you have Node.js installed?                         │
│                                                          │
│  ┌─────────┐                          ┌─────────┐      │
│  │   NO    │                          │   YES   │      │
│  └────┬────┘                          └────┬────┘      │
│       │                                    │            │
│       ↓                                    ↓            │
│  Docker-Only Mode                     Hybrid Mode       │
│  (Recommended)                        (For Devs)        │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## 🐳 Docker-Only Mode

### When to Use
- ✅ You just want to use OntoCode
- ✅ You don't have Node.js
- ✅ You want zero configuration
- ✅ You're deploying to production

### What You Need
- Docker Desktop only

### Installation Command

**Windows:**
```cmd
docker-install.bat
```

**PowerShell:**
```powershell
.\docker-install.ps1
```

**Linux/Mac:**
```bash
./docker-install.sh
```

### Access
**http://localhost:3000**

### What Gets Installed
```
8 Docker Containers:
  ✓ MongoDB (Database)
  ✓ GraphDB (RDF Store)
  ✓ Auth Service
  ✓ API Gateway
  ✓ OWL Editor
  ✓ SWRL Service
  ✓ Plugin Service
  ✓ VS Code Web ⭐
```

---

## 💻 Hybrid Mode

### When to Use
- ✅ You're developing the extension
- ✅ You need hot-reload
- ✅ You want to debug code
- ✅ You have Node.js installed

### What You Need
- Docker Desktop
- Node.js 18+

### Installation Command

**Windows:**
```cmd
install-and-run.bat
```

**PowerShell:**
```powershell
.\install-and-run.ps1
```

**Linux/Mac:**
```bash
./install-and-run.sh
```

### Access
**Opens automatically in browser**

### What Gets Installed
```
7 Docker Containers + Local Extension:
  ✓ MongoDB (Docker)
  ✓ GraphDB (Docker)
  ✓ Auth Service (Docker)
  ✓ API Gateway (Docker)
  ✓ OWL Editor (Docker)
  ✓ SWRL Service (Docker)
  ✓ Plugin Service (Docker)
  ✓ VS Code Extension (Local) ⭐
```

---

## 🤖 Auto-Detection Mode

### Smart Installation
The `install-and-run` scripts automatically detect Node.js and choose the best mode!

**Windows:**
```cmd
install-and-run.bat
```

**If Node.js found:**
→ Uses Hybrid Mode

**If Node.js NOT found:**
→ Falls back to Docker-Only Mode

**You get the best experience automatically!**

---

## 📊 Quick Comparison

| Feature | Docker-Only | Hybrid |
|---------|-------------|--------|
| **Docker** | ✅ Required | ✅ Required |
| **Node.js** | ❌ Not needed | ✅ Required |
| **Install Time** | 8-12 min | 10-15 min |
| **Restart Time** | 1-2 min | 30-60 sec |
| **Hot Reload** | ❌ No | ✅ Yes |
| **VS Code Location** | Docker | Local |
| **Best For** | Users | Developers |
| **Access URL** | :3000 | Varies |

---

## 🚀 Common Commands

### Start OntoCode

**Docker-Only:**
```cmd
docker-install.bat
```

**Hybrid:**
```cmd
install-and-run.bat
```

**Auto-Detect:**
```cmd
install-and-run.bat
```

### Check Status

```cmd
check-status.bat
```
or
```powershell
.\check-status.ps1
```

### View Logs

**All services:**
```cmd
docker compose logs -f
```

**Specific service:**
```cmd
docker compose logs -f vscode-web
docker compose logs -f owl-editor
```

### Restart a Service

```cmd
docker compose restart vscode-web
docker compose restart gateway
```

### Stop Everything

```cmd
docker compose down
```

### Clean Restart

```cmd
docker compose down -v
docker-install.bat
```

---

## 🌐 Service URLs

After installation, access these URLs:

| Service | URL | Description |
|---------|-----|-------------|
| **VS Code Web** | http://localhost:3000 | Main editor (Docker-only) |
| **API Gateway** | http://localhost:80 | API endpoint |
| **Auth Service** | http://localhost:8086 | Authentication |
| **OWL Editor** | http://localhost:8083 | Ontology API |
| **SWRL Service** | http://localhost:8084 | Rules engine |
| **Plugin Service** | http://localhost:8087 | Plugins |
| **GraphDB** | http://localhost:7200 | RDF database |
| **MongoDB** | mongodb://localhost:27017 | Document DB |

---

## 🔧 Troubleshooting

### Docker Not Running

**Error:** "Docker is not running"

**Fix:**
1. Start Docker Desktop
2. Wait for whale icon to stop animating
3. Run installer again

### Port Already in Use

**Error:** "port is already allocated"

**Fix:**
```cmd
docker compose down
docker-install.bat
```

### Service Not Starting

**Fix:**
```cmd
docker compose down -v
docker-install.bat
```

### View Detailed Logs

```cmd
docker compose logs -f <service-name>
```

---

## 📦 What Gets Downloaded?

### First Installation

**Docker Images (~3 GB):**
- node:21-alpine
- mongo:6
- ontotext/graphdb:11.1.1
- maven:3.8-openjdk-21

**Dependencies:**
- Maven packages (Java backend)
- npm packages (VS Code extension)

### Subsequent Runs

Docker uses cached layers - much faster! (1-2 minutes)

---

## 💡 Pro Tips

### Create Desktop Shortcut

```cmd
create-desktop-shortcut.bat
```

Then just double-click "Start OntoCode" on your desktop!

### Quick Restart (No Rebuild)

```powershell
.\docker-install.ps1 -NoBuild
```

### Skip Browser Launch

```powershell
.\docker-install.ps1 -SkipBrowser
```

### Development Workflow

```cmd
# Terminal 1: Watch for changes
cd ontology-vscode-extension
npm run watch

# Terminal 2: Run extension
npm run test-web
```

---

## 📚 Documentation

| Document | Purpose |
|----------|---------|
| [GET_STARTED.md](GET_STARTED.md) | 30-second quick start |
| [INSTALLATION_GUIDE.md](INSTALLATION_GUIDE.md) | Detailed installation guide |
| [QUICK_START.md](QUICK_START.md) | Quick reference |
| [ARCHITECTURE_DIAGRAMS.md](ARCHITECTURE_DIAGRAMS.md) | System architecture |
| [README.md](README.md) | Main documentation |

---

## 🎯 Decision Tree

```
Start Here
    │
    ├─► I just want to use OntoCode
    │   └─► docker-install.bat
    │       └─► http://localhost:3000
    │
    ├─► I want to develop the extension
    │   └─► install-and-run.bat
    │       └─► Opens automatically
    │
    ├─► I'm not sure which mode
    │   └─► install-and-run.bat
    │       └─► Auto-detects best mode
    │
    └─► I want to deploy to production
        └─► docker-install.bat
            └─► Use docker-compose.production.yml
```

---

## ✅ Installation Checklist

### Before Installation

- [ ] Docker Desktop installed
- [ ] Docker Desktop is running
- [ ] (Optional) Node.js 18+ for Hybrid mode

### Docker-Only Installation

- [ ] Run `docker-install.bat`
- [ ] Wait 8-12 minutes
- [ ] Browser opens to http://localhost:3000
- [ ] Start editing ontologies!

### Hybrid Installation

- [ ] Have Node.js installed
- [ ] Run `install-and-run.bat`
- [ ] Wait 10-15 minutes
- [ ] VS Code opens automatically
- [ ] Hot-reload enabled!

### After Installation

- [ ] Can access http://localhost:3000 or auto-opened URL
- [ ] All services show as running in `check-status.bat`
- [ ] Can create/edit OWL files
- [ ] Backend APIs respond

---

## 🆘 Get Help

**Check status:**
```cmd
check-status.bat
```

**View all logs:**
```cmd
docker compose logs -f
```

**Restart everything:**
```cmd
docker compose down
docker-install.bat
```

**Still stuck?**
- Check [TROUBLESHOOTING.md](TROUBLESHOOTING.md)
- View service logs: `docker compose logs <service>`
- Verify Docker is running: `docker ps`

---

**Quick Start:** `docker-install.bat` or `install-and-run.bat`

**Access:** http://localhost:3000

**That's it!** 🎉
