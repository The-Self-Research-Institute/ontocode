# OntoCode — EC2 Docker Compose Deployment Guide

Production deployment runbook using Docker Compose on a single AWS EC2 instance.

---

## 1. Architecture

| Service | Container | Port | Purpose |
|---|---|---|---|
| Gateway | `ontocode-gateway` | 8080 | Spring Cloud Gateway — routes `/api/*`, handles CORS |
| Auth & billing | `ontocode-auth` | 8086 | Users, workspaces, JWT, Stripe, email |
| OWL editor | `ontocode-editor` | 8083 | Ontology mutations, Fuseki integration, in-memory SPARQL cache |
| SWRL service | `ontocode-swrl` | 8084 | SWRL rule evaluation |
| Plugin service | `ontocode-plugin-service` | 8087 | Plugin marketplace |
| Fuseki | `ontocode-fuseki` | 3030 | Apache Jena Fuseki + TDB2 triple store |
| MongoDB | `ontocode-mongo` | 27017 | Primary database |

External:
- **Stripe** — live mode account
- **SMTP relay** — Gmail App Password or AWS SES
- **Domain + TLS cert** — Let's Encrypt via certbot on the host

---

## 2. EC2 instance sizing

| Resource | Recommendation |
|---|---|
| Instance type | `t3.xlarge` (4 vCPU, 16 GB RAM) minimum |
| Storage | 100 GB gp3 root volume |
| OS | Ubuntu 22.04 LTS |
| Ports open | 22 (your IP), 80, 443 |

---

## 3. One-time EC2 setup

```bash
# Docker
sudo apt update && sudo apt install -y ca-certificates curl gnupg
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt update && sudo apt install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker ubuntu
newgrp docker

# Clone repo
cd /opt && sudo mkdir ontocode && sudo chown ubuntu:ubuntu ontocode
git clone https://github.com/The-Self-Research-Institute/ontocode.git ontocode
cd /opt/ontocode
```

---

## 4. Environment configuration

Copy `.env.example` to `.env` and fill in all values:

```bash
cp .env.example .env
nano .env
```

Key variables:

```env
DOCKER_REGISTRY=sindhujacoretopia
VERSION=latest

# MongoDB
MONGO_ROOT_USER=admin
MONGO_ROOT_PASSWORD=<strong-password>
MONGODB_DATABASE=ontocode

# Fuseki
FUSEKI_ADMIN_PASSWORD=<strong-password>
FUSEKI_JVM_ARGS=-Xmx4g -XX:+UseG1GC

# Auth service
JWT_SECRET=<64-byte base64: openssl rand -base64 64>
ADMIN_EMAIL=admin@yourdomain.com
ADMIN_PASSWORD=<strong-password>
ALLOWED_EMAIL_DOMAINS=          # empty = allow all, or "coretopia.com,example.com"

# Email
SMTP_HOST=email-smtp.us-east-1.amazonaws.com
SMTP_PORT=587
SMTP_USERNAME=<aws-ses-user>
SMTP_PASSWORD=<aws-ses-pass>
EMAIL_FROM=noreply@yourdomain.com

# Stripe (live keys)
STRIPE_SECRET_KEY=sk_live_xxx
STRIPE_PUBLISHABLE_KEY=pk_live_xxx
STRIPE_WEBHOOK_SECRET=whsec_xxx
STRIPE_TRIAL_PERIOD_DAYS=14
STRIPE_PRICE_PRO_MONTHLY=price_xxx
STRIPE_PRICE_PRO_YEARLY=price_xxx
STRIPE_PRICE_ENTERPRISE_MONTHLY=price_xxx
STRIPE_PRICE_ENTERPRISE_YEARLY=price_xxx

# URLs
BASE_URL=https://app.yourdomain.com
```

---

## 5. Building images

### Build all services (from your laptop or EC2)

```bash
./build-and-push.sh sindhujacoretopia latest
```

### Build specific services only

```bash
# Single service
./build-and-push.sh sindhujacoretopia latest editor

# Multiple services
./build-and-push.sh sindhujacoretopia latest auth editor

# Available service names:
#   graphdb  auth  gateway  editor  swrl  plugin  plugin-init  web
```

### Windows (from laptop)

```bat
build-and-push.bat sindhujacoretopia latest editor
build-and-push.bat sindhujacoretopia latest auth gateway
build-and-push.bat sindhujacoretopia latest          :: all services
```

---

## 6. Deploying on EC2

### First-time full deploy

```bash
cd /opt/ontocode
docker compose pull
docker compose up -d
```

### Deploy after building a specific service

```bash
# After building editor on laptop and pushing:
docker compose pull owl-editor
docker compose up -d owl-editor

# After building auth:
docker compose pull ontology-auth
docker compose up -d ontology-auth

# After building gateway:
docker compose pull gateway
docker compose up -d gateway
```

### Deploy all updated images

```bash
docker compose pull
docker compose up -d
```

---

## 7. Service names in docker-compose vs build script

| Build arg | docker-compose service | Container name |
|---|---|---|
| `editor` | `owl-editor` | `ontocode-editor` |
| `auth` | `ontology-auth` | `ontocode-auth` |
| `gateway` | `gateway` | `ontocode-gateway` |
| `swrl` | `swrl-service` | `ontocode-swrl` |
| `plugin` | `plugin-service` | `ontocode-plugin` |
| `plugin-init` | `plugin-init` | `ontocode-plugin-init` |
| `web` | `webapp` | `ontocode-web` |

---

## 8. TDB2 query optimizer (one-time, after large data loads)

Generate statistics for better Fuseki query planning:

```bash
# Run during off-hours — requires stopping Fuseki briefly
docker compose stop fuseki
bash scripts/fuseki-stats.sh
docker compose start fuseki
```

Re-run after adding any large ontology (>500K triples).

---

## 9. Logs

```bash
# All services
docker compose logs -f

# Single service
docker compose logs -f owl-editor
docker compose logs -f ontology-auth
docker compose logs -f ontocode-fuseki

# Last N lines
docker compose logs --tail=200 owl-editor

# Grep for errors
docker compose logs owl-editor 2>&1 | grep -i "error\|warn\|PERF"

# Fuseki warmup progress
docker compose logs ontocode-fuseki 2>&1 | grep WARMUP
```

---

## 10. Health checks

```bash
# All containers
docker compose ps

# Gateway
curl -fs http://localhost:8080/actuator/health

# Auth
curl -fs http://localhost:8086/actuator/health

# Fuseki
curl -fs -u admin:${FUSEKI_ADMIN_PASSWORD} http://localhost:3030/$/ping

# MongoDB
docker exec ontocode-mongo mongosh -u admin -p ${MONGO_ROOT_PASSWORD} \
  --eval "db.adminCommand({ping:1})"
```

---

## 11. Troubleshooting

| Symptom | Check |
|---|---|
| 504 on top-level classes | `docker compose logs owl-editor \| grep PERF` — look for Phase 1 duration. If >5s, TDB2 is cold. Wait for warmup or restart editor to re-trigger warmup. |
| Fuseki unhealthy | `docker compose logs ontocode-fuseki` — check if `No services found`. Run `bash scripts/fuseki-stats.sh` or verify `fuseki-config.ttl` is mounted correctly. |
| 404 on `/api/projects/.../files` | `docker compose logs ontology-auth \| grep ProjectService` — workspace accessibility check failed. Check enterprise domains or subscription status. |
| MongoDB connection refused | `docker compose ps ontocode-mongo` — ensure healthy. Check `MONGODB_URI` in .env. |
| CORS errors | `docker compose logs gateway \| grep CORS` — likely ALB timeout (60s). The request itself timed out upstream. |
| Editor OOM | Increase `FUSEKI_JVM_ARGS` or reduce `ONTOCODE_MEMCACHE_MAX_PROJECTS` in .env. |
| Build fails on EC2 | `docker buildx rm ontocode-builder && docker buildx prune -af` then retry. |

---

## 12. Rollback

```bash
# Roll back a single service to previous version
docker compose stop owl-editor
docker compose rm -f owl-editor
VERSION=previous-tag docker compose up -d owl-editor

# Or pull a specific tag
docker pull sindhujacoretopia/ontocode-editor:v1.2.3
VERSION=v1.2.3 docker compose up -d owl-editor
```

---

## 13. Cheat sheet

```bash
# Build + push single service (from laptop)
./build-and-push.sh sindhujacoretopia latest editor

# Deploy on EC2
docker compose pull owl-editor && docker compose up -d owl-editor

# Restart a service
docker compose restart owl-editor

# Shell into a container
docker exec -it ontocode-editor bash

# Check Fuseki SPARQL manually
curl -u admin:admin "http://localhost:3030/ontocode/query" \
  --data-urlencode "query=SELECT (COUNT(*) AS ?c) WHERE { ?s ?p ?o }"

# Generate TDB2 stats (off-hours)
docker compose stop fuseki && bash scripts/fuseki-stats.sh && docker compose start fuseki

# MongoDB shell
docker exec -it ontocode-mongo mongosh -u admin -p <password>

# Tail editor logs for performance
docker compose logs -f owl-editor | grep -E "PERF|WARMUP|MEMCACHE|TLCACHE"
```

---

## 14. Jena/Fuseki 6.1.0 migration fixes (May 2026)

These fixes are **baked into the images** — any new instance pulling `latest` gets them automatically. This section is reference for what changed and why, and covers extra steps needed when the **existing TDB2 data was written by Fuseki 5.0.0**.

---

### What changed in the code

| File | Change | Why |
|---|---|---|
| `ontology-editor/.../GraphDBDatasetService.java` | `clearDataset()` now uses `CLEAR GRAPH <uri>` instead of `DELETE WHERE { GRAPH … { ?s ?p ?o } }` | Fuseki 5.0.0 had a write bug that left zero bytes in TDB2 node table entries. `DELETE WHERE` iterates the node table and crashes with `NodeTableTRDF/Read: Unrecognized type 0`. `CLEAR GRAPH` operates at the B-tree index level and never reads node values. |
| `ontology-editor/.../OntologyQueryService.java` | Removed 8 `System.out.println` calls from `mapTreeNodes()` and the annotation properties method | Each call flushed through Docker's log driver — for a class with 300+ children that was 1500+ blocking stdout writes before the HTTP response returned. |
| `ontology-vscode-extension/.../Dashboard.tsx` | Removed 5-second `Promise.race` timeout that silently collapsed expanded tree nodes | When the backend took >5s on the first (uncached) call, the timeout fired, the node was removed from `expandedNodes`, and the user saw nothing. Spring's `@Cacheable` made the second click fast so it appeared to work only on the second try. Now the spinner stops at 30s but the node stays open; children appear when the API responds. |
| `fuseki-docker/` | New directory — custom Apache Jena Fuseki 6.1.0 Docker image | No `stain/jena-fuseki:6.1.0` exists on Docker Hub; built from the official Apache Jena 6.1.0 binary. TDB1 was dropped in Jena 6.x. |
| `docker-compose.production.yml` | `fuseki` service now pulls `${DOCKER_REGISTRY}/ontocode-fuseki:6.1.0` and runs with `user: root` | Custom registry image; `user: root` prevents `AccessDeniedException` on the TDB2 lock file when the volume was previously owned by another user/container. |
| `build-and-push.sh` / `build-and-push.bat` | Added `fuseki` service build (linux/amd64 only) | EC2 is x86_64; ARM64 QEMU emulation caused 4000s+ build times. |

---

### Fresh instance (no existing TDB2 data)

Nothing extra needed. Pull and start:

```bash
cd /opt/ontocode
git pull                          # get updated docker-compose.production.yml
docker compose -f docker-compose.production.yml pull
docker compose -f docker-compose.production.yml up -d
```

---

### Existing instance with Fuseki 5.0.0 TDB2 data (corruption scenario)

If the TDB2 database was previously written by Fuseki 5.0.0 you may see this in logs:

```
TProtocolException: Unrecognized type 0
NodeTableTRDF/Read: ...
```

#### Step 1 — Stop editor, keep Fuseki running

```bash
docker compose -f docker-compose.production.yml stop owl-editor
```

#### Step 2 — Run Fuseki compact to skip corrupt entries

```bash
# Get the admin password from .env
source .env

# Trigger compact — this rebuilds the TDB2 segment, skipping corrupt entries
curl -s -X POST -u admin:${FUSEKI_ADMIN_PASSWORD} \
  "http://localhost:3030/$/compact/ontocode?deleteOld=true"

# Check compact task status
curl -s -u admin:${FUSEKI_ADMIN_PASSWORD} http://localhost:3030/$/tasks | python3 -m json.tool
# Wait until the task shows "finishPoint" timestamp (may take 2-5 minutes for large datasets)
```

#### Step 3 — Verify data is readable

```bash
curl -s -u admin:${FUSEKI_ADMIN_PASSWORD} \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode "query=SELECT (COUNT(*) AS ?c) WHERE { ?s ?p ?o }" \
  "http://localhost:3030/ontocode/query"
# Should return a count, not a NodeTableTRDF error
```

#### Step 4 — Clear graphs that had partial imports before re-importing

```bash
# Replace <graph-uri> with the actual project graph URI (visible in Fuseki UI or editor logs)
curl -s -X POST \
  -H "Content-Type: application/sparql-update" \
  -u admin:${FUSEKI_ADMIN_PASSWORD} \
  --data "CLEAR GRAPH <http://ontocode.org/project/YOUR-PROJECT-ID>" \
  "http://localhost:3030/ontocode/update"
```

#### Step 5 — Pull and restart editor

```bash
docker compose -f docker-compose.production.yml pull owl-editor webapp
docker compose -f docker-compose.production.yml up -d owl-editor webapp
```

Then re-trigger any imports that previously failed with `NodeTableTRDF/Read`.

---

### Build commands used (reference)

```bash
# Build and push Fuseki custom image (amd64 only — EC2 is x86_64)
docker buildx create --name ontocode-builder --use --driver docker-container
docker buildx build --platform linux/amd64 \
  -t sindhujacoretopia/ontocode-fuseki:6.1.0 \
  -f fuseki-docker/Dockerfile --push fuseki-docker

# Build and push editor (contains CLEAR GRAPH fix + no println)
docker buildx build --platform linux/amd64 \
  -t sindhujacoretopia/ontocode-editor:latest \
  -f Dockerfile.editor --push .

# Build and push webapp (contains tree expand fix)
docker buildx build --platform linux/amd64 \
  -t sindhujacoretopia/ontocode-web:latest \
  -f Dockerfile.webapp --push .

# Or use the script to build everything at once:
./build-and-push.sh sindhujacoretopia latest
```

---

### EC2 deploy commands (reference)

```bash
# Deploy both fixed services
docker compose -f docker-compose.production.yml pull owl-editor webapp
docker compose -f docker-compose.production.yml up -d owl-editor webapp

# Verify they're running
docker compose -f docker-compose.production.yml ps
docker compose -f docker-compose.production.yml logs --tail=50 owl-editor
docker compose -f docker-compose.production.yml logs --tail=50 webapp
```

---

### Probability these fixes are needed on a new instance

| Fix | New fresh instance | Instance migrated from 5.0.0 |
|---|---|---|
| Fuseki 6.1.0 custom image | **Required** (no stain/jena-fuseki:6.1.0 on Hub) | **Required** |
| `CLEAR GRAPH` in editor | Baked in image — no action needed | **Required** or imports will crash |
| `println` removal in editor | Baked in image — no action needed | Baked in image — no action needed |
| Tree expand timeout fix | Baked in image — no action needed | Baked in image — no action needed |
| TDB2 compact step | Not needed | **Required** if imports previously failed |

---

## 15. Security checklist

- [ ] `.env` file has mode `0600` and is in `.gitignore`
- [ ] `JWT_SECRET` is 64+ bytes from `openssl rand -base64 64`
- [ ] Stripe keys are live mode (`sk_live_`, `pk_live_`, `whsec_`)
- [ ] `FUSEKI_ADMIN_PASSWORD` is strong (not `admin`)
- [ ] `MONGO_ROOT_PASSWORD` is strong
- [ ] Port 3030 (Fuseki) and 27017 (MongoDB) NOT open to public in EC2 Security Group
- [ ] Port 7200 (GraphDB) NOT open — service no longer uses GraphDB
- [ ] Backups running for MongoDB (`mongodump`) and Fuseki TDB2 data volume
- [ ] `ALLOWED_EMAIL_DOMAINS` set appropriately for restricted testing phases

---

## 16. Desktop (Electron) build

The desktop app bundles all backend services as JARs inside the Electron package.
Use `build-desktop.sh` / `build-desktop.bat` instead of `build-and-push.sh`.

### Architecture

Auth + OWL editor + plugin are combined into a single `desktop.jar` (one JVM, port 18083).
SWRL stays as a separate JVM because owlapi 4.x (SWRL) and owlapi 5.x (editor) cannot
share a classloader.

| Service | JAR | Port | Notes |
|---|---|---|---|
| MongoDB | system binary or bundled | 27117 | local port, offset from default |
| Fuseki | `fuseki-server.jar` | 13030 | local port, offset from default |
| Desktop | `desktop.jar` | 18083 | auth + OWL editor + plugin (one JVM) |
| SWRL | `swrl.jar` | 18084 | optional, skipped if JAR absent |
| Node proxy | (built-in) | 18085 | `/api/swrl/**` → 18084, everything else → 18083 |

The React UI points at `http://127.0.0.1:18085` (proxy port).

### Build scripts

| Script | Platform |
|---|---|
| `build-desktop.sh` | macOS / Linux |
| `build-desktop.bat` | Windows |

### Usage

```bash
# Build everything and package for Windows
build-desktop.bat win

# Build everything and package for macOS (run on Mac)
./build-desktop.sh mac

# Rebuild only the combined desktop.jar (auth + editor + plugin — fast backend iteration)
build-desktop.bat win desktop

# Rebuild only the React UI (no Maven build)
build-desktop.bat win ui

# Repackage Electron only (JARs and UI already built)
build-desktop.bat win pack
```

Available step names: `desktop` `swrl` `jars` (desktop + swrl) `ui` `pack`

### Maven build order

`desktop.jar` is built from `ontology-desktop`, which depends on:
1. `shared/common-models`, `shared/common-utils` (install first)
2. `ontology-auth`, `ontology-editor` (install — must use `classifier=exec` so the
   primary artifact remains a plain JAR usable as a dependency)
3. `ontology-plugin-service` (install)
4. `ontology-desktop` (package — produces the fat JAR)

Web/cloud deployment is unaffected: each module still deploys as its own Docker service.

### Output

The packaged installer is written to `electron-app/dist/`.

### JAR locations

| Source | Destination |
|---|---|
| `ontology-desktop/target/ontology-desktop-1.0.0.jar` | `electron-app/resources/backend/jars/desktop.jar` |
| `ontology-swrl/target/ontology-swrl-1.0.0.jar` | `electron-app/resources/backend/jars/swrl.jar` |

### Dev mode

Set `ELECTRON_IS_DEV=1` to skip bundled service startup and connect to the
running Docker stack instead (default Docker URL `http://localhost:8083`):

```bat
set ELECTRON_IS_DEV=1 && npx electron electron-app
```
