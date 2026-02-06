# OntoCode - One-Click Install (No Source Code Required)

Now configured to use **pre-built Docker images** from a container registry.

## For End Users (One-Click Install)

### Prerequisites
- Docker Desktop installed and running
- Internet connection to pull images

### Required Files (Only 2!)
1. `docker-compose.yml` - Main configuration
2. `.env` - Registry configuration (create from template below)

### Setup Steps

**1. Create `.env` file:**
```env
DOCKER_REGISTRY=ghcr.io/yourusername
VERSION=latest
MONGODB_DATABASE=ontocode
MONGO_ROOT_USER=admin
MONGO_ROOT_PASSWORD=changeme123
```

**2. Create data directory:**
```bash
mkdir -p data/projects
```

**3. Start everything:**
```bash
docker compose up -d
```

That's it! All images will be pulled automatically from the registry.

### What You Get
- ✅ MongoDB (database)
- ✅ GraphDB with "ontocode" repository (inference disabled)
- ✅ Authentication service
- ✅ API Gateway
- ✅ Ontology Editor backend
- ✅ SWRL Rules engine
- ✅ Plugin service
- ✅ VS Code Web editor on http://localhost:3000

### Verify Installation
```bash
docker compose ps
# All 10 services should be running
```

### Access Points
- **VS Code Web**: http://localhost:3000
- **GraphDB**: http://localhost:7200
- **API Gateway**: http://localhost:80

---

## For Developers (Building and Publishing Images)

### Build All Images Locally
```bash
# Build without pushing (for testing)
docker compose build
```

### Push Images to Registry

**Option 1: Using script (Recommended)**
```bash
# Windows
build-and-push.bat ghcr.io/yourusername latest

# Linux/Mac
chmod +x build-and-push.sh
./build-and-push.sh ghcr.io/yourusername latest
```

**Option 2: Manual**
```bash
# Login to your registry
docker login ghcr.io

# Build and push
docker compose build
docker compose push
```

### Supported Registries
- **Docker Hub**: `docker.io/yourusername`
- **GitHub Container Registry**: `ghcr.io/yourusername`
- **GitLab Registry**: `registry.gitlab.com/yourusername/project`
- **AWS ECR**: `123456789.dkr.ecr.region.amazonaws.com`
- **Azure ACR**: `yourregistry.azurecr.io`
- **Private Registry**: `your-registry.com:5000`

### Publishing to GitHub Container Registry (Example)

```bash
# 1. Create GitHub Personal Access Token with write:packages permission

# 2. Login
echo $GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin

# 3. Build and push
./build-and-push.sh ghcr.io/USERNAME latest

# 4. Make packages public (optional)
# Go to: https://github.com/users/USERNAME/packages
# Click on each package → Package settings → Change visibility to Public
```

### Version Tags
```bash
# Latest version
./build-and-push.sh ghcr.io/yourusername latest

# Specific version
./build-and-push.sh ghcr.io/yourusername v1.0.0

# Development version
./build-and-push.sh ghcr.io/yourusername dev
```

---

## Distribution Package

To distribute OntoCode to end users, provide only:

1. **docker-compose.yml**
2. **.env.example** (rename to .env and configure)
3. **README** with setup instructions

**Total size**: ~5KB (just 2 text files!)

No source code, no Dockerfiles, no build tools required.

---

## Migration from Source Build

If you have existing local images, tag and push them:

```bash
# Tag existing images
docker tag ontocode-graphdb:latest ghcr.io/yourusername/ontocode-graphdb:latest
docker tag ontocode-auth:latest ghcr.io/yourusername/ontocode-auth:latest
# ... repeat for all 8 images

# Push all
docker push ghcr.io/yourusername/ontocode-graphdb:latest
# ... repeat for all 8 images
```

---

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Build and Push Images

on:
  push:
    branches: [main]
    tags: ['v*']

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Login to GitHub Container Registry
        uses: docker/login-action@v2
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      
      - name: Build and Push
        run: |
          ./build-and-push.sh ghcr.io/${{ github.repository_owner }} ${GITHUB_REF#refs/tags/}
```

---

## Troubleshooting

### Cannot pull images
- Ensure you have access to the registry
- Login: `docker login ghcr.io`
- Check registry URL in `.env` file

### Old images cached
```bash
docker compose pull  # Pull latest images
docker compose up -d --force-recreate
```

### Build images locally instead
```bash
# Temporarily switch to local build
git clone <repository>
cd ontocode
docker compose -f docker-compose.original.yml up -d
```
