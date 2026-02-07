# 🔄 Docker Auto-Reload Configuration

## Overview
OntoCode Docker services are configured for automatic updates when you change code. Here's how each service handles live reload:

## 📦 Services with Auto-Reload

### 1. **webview-dev** (Port 3000) ✅ FULLY AUTOMATIC
```yaml
volumes:
  - ./ontology-vscode-extension/webview-src:/app
```

**What updates automatically:**
- All React components (.tsx files)
- Styles (.css files)
- TypeScript files in webview-src/
- Configuration files (vite.config.ts)

**How it works:**
- Vite dev server watches mounted files
- Changes trigger instant Hot Module Replacement (HMR)
- Browser auto-refreshes within milliseconds
- No rebuild needed

**Test it:**
1. Edit `ontology-vscode-extension/webview-src/App.tsx`
2. Save the file
3. Browser at http://localhost:3000 updates instantly

---

### 2. **vscode-web-test** (Port 3001) ✅ VOLUME MOUNTED
```yaml
volumes:
  - .:/workspace
```

**What's mounted:**
- Entire OntoCode repository
- Extension source code
- Webview source code
- All configuration files

**Current limitation:**
- Webpack bundle needs rebuild after changes
- Server doesn't auto-restart

**To see changes:**
```bash
# Option 1: Restart service (fast)
docker-compose restart vscode-web-test

# Option 2: Rebuild and restart (if webpack config changed)
docker-compose up -d --build vscode-web-test
```

**Future improvement:**
Add webpack watch mode for automatic rebuilds

---

### 3. **webview-test** (Port 8089) ❌ NO AUTO-RELOAD
```dockerfile
COPY ontology-vscode-extension/webview-src ./
RUN npm run build
```

**Why no auto-reload:**
- Production build baked into Docker image
- Files copied during build, not mounted
- Intended for final testing, not development

**To see changes:**
```bash
docker-compose build webview-test
docker-compose up -d webview-test
```

**Use this for:**
- Testing production build
- Performance testing
- Final QA before release

---

### 4. **Backend Services** (Gateway, Editor, Auth, etc.)

#### Current State: ❌ NO AUTO-RELOAD
```yaml
# No volumes mounted for source code
```

#### To Enable Auto-Reload:

**Option A: Add Volume Mounting (Recommended for Development)**

Add to `docker-compose.yml`:
```yaml
ontology-editor:
  volumes:
    - ./ontology-editor/src:/app/src:ro  # Read-only source
    - ./ontology-editor/target:/app/target  # Compiled classes
  environment:
    - SPRING_DEVTOOLS_RESTART_ENABLED=true
```

**Option B: Use Spring Boot DevTools**

1. Add to `pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
    <optional>true</optional>
</dependency>
```

2. Mount source:
```yaml
volumes:
  - ./ontology-editor/src:/app/src
```

**Option C: Keep Current (Manual Rebuild)**
```bash
# After changing Java code:
docker-compose build ontology-editor
docker-compose up -d ontology-editor
```

---

## 🎯 Quick Reference

| Service | Port | Auto-Reload | How to Update |
|---------|------|-------------|---------------|
| **webview-dev** | 3000 | ✅ Instant | Just save file - updates in <1s |
| **vscode-web-test** | 3001 | ⚠️ Mounted | `docker-compose restart vscode-web-test` |
| **webview-test** | 8089 | ❌ No | `docker-compose up -d --build webview-test` |
| **extension-server** | 8088 | ❌ No | `docker-compose up -d --build extension-server` |
| **Backend (Java)** | Various | ❌ No | `docker-compose up -d --build <service>` |

---

## 🚀 Best Development Workflow

### Frontend Development
```bash
# Start with hot reload
docker-compose up -d webview-dev

# Edit files in: ontology-vscode-extension/webview-src/
# Changes appear instantly at http://localhost:3000
```

### Extension Development
```bash
# Start VS Code Web
docker-compose up -d vscode-web-test

# Edit extension code
# Then restart:
docker-compose restart vscode-web-test

# Access at http://localhost:3001
```

### Backend Development
```bash
# Current workflow (no auto-reload):
# 1. Edit Java files
# 2. Rebuild:
docker-compose up -d --build ontology-editor

# Future workflow (with DevTools):
# 1. Edit Java files
# 2. Save
# 3. Wait 5-10 seconds
# 4. Service auto-restarts
```

---

## 💡 Enabling Full Auto-Reload for All Services

Create `docker-compose.dev.yml`:

```yaml
version: '3.8'

services:
  # Webview dev already has auto-reload
  webview-dev:
    volumes:
      - ./ontology-vscode-extension/webview-src:/app

  # Add auto-reload to vscode-web-test
  vscode-web-test:
    volumes:
      - .:/workspace
    command: sh -c "npm install && npm run bundle:web -- --watch & npx vscode-test-web --browser=none --extensionDevelopmentPath=/workspace/ontology-vscode-extension /workspace/data/projects"

  # Add auto-reload to backend
  ontology-editor:
    volumes:
      - ./ontology-editor/src:/app/src:ro
      - ./ontology-editor/target:/app/target
    environment:
      - SPRING_DEVTOOLS_RESTART_ENABLED=true
      - SPRING_DEVTOOLS_RESTART_POLL_INTERVAL=1000
      - SPRING_DEVTOOLS_RESTART_QUIET_PERIOD=500

  ontology-gateway:
    volumes:
      - ./ontology-gateway/src:/app/src:ro
      - ./ontology-gateway/target:/app/target
    environment:
      - SPRING_DEVTOOLS_RESTART_ENABLED=true

  # Add for other services...
```

**Use it:**
```bash
# Development mode with auto-reload
docker-compose -f docker-compose.yml -f docker-compose.dev.yml up -d

# Production mode (current)
docker-compose up -d
```

---

## 🔧 Troubleshooting Auto-Reload

### Webview Changes Not Appearing

**Check if service is running:**
```bash
docker ps | grep webview-dev
```

**Check Vite logs:**
```bash
docker logs -f ontocode-webview-dev
```

**Hard refresh browser:**
- Windows/Linux: `Ctrl+Shift+R`
- Mac: `Cmd+Shift+R`

### Backend Changes Not Applying

**Verify Spring DevTools is active:**
```bash
docker logs ontocode-editor | grep "DevTools"
```

**Check if source is mounted:**
```bash
docker exec ontocode-editor ls -la /app/src
```

**Manual rebuild if needed:**
```bash
docker-compose up -d --build ontology-editor
```

### File Permissions Issues (Linux/Mac)

**If changes aren't detected:**
```bash
# Fix ownership
sudo chown -R $USER:$USER ./ontology-vscode-extension/webview-src

# Fix permissions
chmod -R 755 ./ontology-vscode-extension/webview-src
```

---

## 📊 Performance Impact

| Method | Startup Time | Update Time | CPU Usage | Disk I/O |
|--------|--------------|-------------|-----------|----------|
| **HMR (webview-dev)** | 20s | <1s | Low | Low |
| **Restart (vscode-web)** | 10s | 10s | Medium | Low |
| **Rebuild (production)** | 3min | 3min | High | High |
| **DevTools (backend)** | 30s | 5-10s | Medium | Medium |

---

## ✅ Current Status Summary

**Automatic (No Action Needed):**
- ✅ webview-dev (port 3000) - Edit React files, see changes instantly

**Semi-Automatic (Restart Only):**
- ⚠️ vscode-web-test (port 3001) - Restart service after edits

**Manual (Rebuild Required):**
- ❌ webview-test (port 8089)
- ❌ extension-server (port 8088)
- ❌ All backend services (Gateway, Editor, Auth, etc.)

**Recommendation:**
- Use **webview-dev** for all React development
- Use **vscode-web-test** for extension testing (restart between changes)
- Use **webview-test** only for final production testing
- Consider adding Spring DevTools for backend auto-reload

---

## 🎓 Learn More

- **Vite HMR:** https://vitejs.dev/guide/features.html#hot-module-replacement
- **Spring DevTools:** https://docs.spring.io/spring-boot/docs/current/reference/html/using.html#using.devtools
- **Docker Volumes:** https://docs.docker.com/storage/volumes/
- **VS Code Test Web:** https://github.com/microsoft/vscode-test-web

---

**Last Updated:** February 2026  
**Auto-Reload Status:** Partial (frontend only)  
**Recommended:** Enable DevTools for backend auto-reload
