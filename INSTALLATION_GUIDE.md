# 🚀 OntoCode Installation Guide

## Two Installation Modes

OntoCode offers **two installation modes** to suit different needs:

### 🐳 Mode 1: Docker-Only (Recommended for Most Users)

**Perfect for:**
- Users who just want to run the platform
- Production deployments
- Users without Node.js
- Minimal local dependencies

**Requirements:**
- ✅ Docker Desktop only
- ❌ No Node.js required
- ❌ No source code compilation needed

**Install Command:**
```cmd
docker-install.bat
```

Everything runs in Docker containers, including the VS Code web editor!

---

### 💻 Mode 2: Hybrid (For Developers)

**Perfect for:**
- Active development on the VS Code extension
- Hot-reloading during development
- Debugging the extension code

**Requirements:**
- ✅ Docker Desktop
- ✅ Node.js 18+
- ✅ Source code access

**Install Command:**
```cmd
install-and-run.bat
```

Backend runs in Docker, VS Code extension runs locally with hot-reload.

---

## Quick Start

### Windows

**Docker-Only Mode** (No Node.js needed):
```cmd
docker-install.bat
```

**Hybrid Mode** (With Node.js):
```cmd
install-and-run.bat
```

**PowerShell** (Auto-detects mode):
```powershell
.\install-and-run.ps1
```

### Linux/Mac

**Docker-Only Mode**:
```bash
chmod +x docker-install.sh
./docker-install.sh
```

**Hybrid Mode**:
```bash
chmod +x install-and-run.sh
./install-and-run.sh
```

---

## Detailed Comparison

| Feature | Docker-Only | Hybrid |
|---------|-------------|--------|
| **Docker Required** | ✅ Yes | ✅ Yes |
| **Node.js Required** | ❌ No | ✅ Yes (18+) |
| **Source Code Required** | ❌ No | ✅ Yes |
| **VS Code Editor Location** | 🐳 In Docker | 💻 Local |
| **VS Code Editor URL** | http://localhost:3000 | http://localhost:8000 (varies) |
| **Extension Hot Reload** | ❌ No | ✅ Yes |
| **Build Time (First)** | 8-12 min | 10-15 min |
| **Build Time (Subsequent)** | 1-2 min | 30-60 sec |
| **Disk Space** | ~3 GB | ~4 GB |
| **Best For** | End users, Production | Developers |

---

## What Gets Installed?

Both modes install these backend services in Docker:

| Service | Port | Description |
|---------|------|-------------|
| **MongoDB** | 27017 | Document database |
| **GraphDB** | 7200 | RDF triple store |
| **Auth Service** | 8086 | Authentication API |
| **API Gateway** | 80 | Main API endpoint |
| **OWL Editor** | 8083 | Ontology operations |
| **SWRL Service** | 8084 | SWRL rules engine |
| **Plugin Service** | 8087 | Plugin management |

### Additional in Docker-Only Mode:

| Service | Port | Description |
|---------|------|-------------|
| **VS Code Web** | 3000 | Web-based VS Code editor |

---

## Step-by-Step: Docker-Only Installation

### 1. Install Docker Desktop

Download from: https://www.docker.com/products/docker-desktop

**Windows:**
- Install Docker Desktop
- Ensure WSL 2 is enabled (installer will guide you)
- Start Docker Desktop

**Linux:**
```bash
# Ubuntu/Debian
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER
# Log out and back in
```

**Mac:**
- Install Docker Desktop from the website
- Start Docker Desktop

### 2. Run the Installer

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
chmod +x docker-install.sh
./docker-install.sh
```

### 3. Wait for Build

- First run: 8-12 minutes
- Docker downloads images
- Builds all containers
- Starts all services

### 4. Access VS Code Web

The installer will automatically open http://localhost:3000 in your browser.

Or manually visit: **http://localhost:3000**

### 5. Start Editing

1. The VS Code web editor opens
2. Use `Ctrl+Shift+P` (or `Cmd+Shift+P` on Mac)
3. Type "OntoCode: Edit"
4. Select an OWL file to edit

---

## Step-by-Step: Hybrid Installation

### 1. Install Prerequisites

**Docker Desktop** (see above)

**Node.js 18+:**
- Download from: https://nodejs.org/
- Verify: `node --version` (should show v18 or higher)

### 2. Run the Installer

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
chmod +x install-and-run.sh
./install-and-run.sh
```

### 3. What Happens

1. Backend services start in Docker (5 min)
2. Extension dependencies install (`npm install`)
3. Extension builds (`npm run bundle:web`)
4. VS Code web server starts locally
5. Browser opens automatically

### 4. Development Workflow

The extension runs locally, so you can:
- Edit extension source code
- Changes rebuild automatically (with webpack watch)
- Refresh browser to see changes
- Debug with VS Code's built-in debugger

---

## Switching Between Modes

### From Docker-Only to Hybrid

1. Stop Docker-only:
   ```cmd
   docker compose down
   ```

2. Install Node.js

3. Run hybrid installer:
   ```cmd
   install-and-run.bat
   ```

### From Hybrid to Docker-Only

1. Stop everything:
   ```cmd
   docker compose down
   ```

2. Run Docker-only installer:
   ```cmd
   docker-install.bat
   ```

The Docker-only mode works even without Node.js!

---

## Auto-Detection Mode

The `install-and-run` scripts **automatically detect** if Node.js is available:

**If Node.js is installed:**
- ✅ Uses Hybrid mode (local extension + Docker backend)

**If Node.js is NOT installed:**
- ✅ Automatically falls back to Docker-only mode
- ⚠️ Shows a warning that it's using Docker-only mode

**Try it:**
```cmd
install-and-run.bat
```

The script will choose the best mode for your system!

---

## Troubleshooting

### Docker Not Running

**Error:** "Docker is not running"

**Solution:**
1. Start Docker Desktop
2. Wait for it to fully start (whale icon stops animating)
3. Run the installer again

### Port Already in Use

**Error:** "port is already allocated"

**Solution:**
```cmd
docker compose down
netstat -ano | findstr :3000
taskkill /PID <PID> /F
```

### VS Code Web Not Loading

**Docker-Only Mode:**
```cmd
# Check if container is running
docker compose ps

# View logs
docker compose logs vscode-web

# Restart the service
docker compose restart vscode-web
```

**Hybrid Mode:**
```cmd
cd ontology-vscode-extension
npm run test-web
```

### Build Failed

**Clean rebuild:**
```cmd
docker compose down -v
docker system prune -a
docker-install.bat
```

---

## Advanced Usage

### Docker-Only: Custom Workspace Path

```powershell
$env:WORKSPACE_PATH = "C:\MyOntologies"
docker compose up -d
```

### Hybrid: Development with Watch Mode

```cmd
cd ontology-vscode-extension
npm run watch
```
In another terminal:
```cmd
npm run test-web
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

### Stop and Remove All Data

```cmd
docker compose down -v
```

---

## Which Mode Should I Use?

### Use Docker-Only If:
- ✅ You just want to use OntoCode
- ✅ You're deploying to production
- ✅ You don't have Node.js installed
- ✅ You want fewer dependencies
- ✅ You prefer everything containerized

### Use Hybrid If:
- ✅ You're developing the VS Code extension
- ✅ You need hot-reload for extension code
- ✅ You want to debug the extension
- ✅ You're contributing to the project
- ✅ You have Node.js installed anyway

### Still Not Sure?

**Try Docker-Only first!** It's simpler and works for 95% of use cases.

```cmd
docker-install.bat
```

---

## Next Steps

1. ✅ Run the installer
2. 📖 Read the [QUICK_START.md](QUICK_START.md)
3. 🎓 Follow the [User Guide](README.md)
4. 🧪 Try the [Examples](TESTING_GUIDE.md)

---

**Questions?**

- Check service status: `docker compose ps`
- View logs: `docker compose logs -f`
- Restart services: `docker compose restart`

**Happy ontology editing! 🧩**
