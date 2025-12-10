# Ontology Plugin Service

A Spring Boot 3.4 microservice for managing OntoCode plugins. Provides a remote plugin marketplace to keep the VS Code extension lightweight.

## Architecture

**Phase 1: Minimal Cost (Current)**
- **Storage**: MongoDB GridFS (zero cost, uses existing MongoDB)
- **Authentication**: JWT validation (reuses ontology-auth)
- **Deployment**: Docker container on existing infrastructure

**Migration Path**:
- Phase 2: Add S3 + CloudFront CDN (when traffic grows)
- Phase 3: Add Redis caching (when needed)
- Phase 4: Add code signing & sandboxing (security hardening)

All storage operations use abstraction layer (`PluginStorageService`) for easy migration.

## Technology Stack

- **Java 21** with Spring Boot 3.4
- **MongoDB** for plugin metadata
- **GridFS** for VSIX file storage
- **JWT** for authentication
- **Docker** for deployment

## API Endpoints

### Public (No Auth Required)

```
GET  /api/plugins                 - Browse plugins (paginated)
GET  /api/plugins/search          - Search plugins
GET  /api/plugins/{id}            - Get plugin details
GET  /api/plugins/{id}/versions   - Get all versions
GET  /api/plugins/{id}/download   - Download VSIX file
```

### Protected (JWT Required)

```
POST /api/plugins                 - Publish new plugin/version
```

## Running Locally

### Prerequisites
- Java 21
- MongoDB running on localhost:27017
- Maven

### Development Mode

```bash
cd ontology-plugin-service
mvn spring-boot:run
```

Service runs on `http://localhost:8087`

### Docker Mode

```bash
# From project root
docker-compose up plugin-service
```

## Configuration

Edit `src/main/resources/application.properties`:

```properties
# MongoDB
spring.data.mongodb.host=localhost
spring.data.mongodb.port=27017
spring.data.mongodb.database=ontology

# JWT Secret (must match ontology-auth)
jwt.secret=your-secret-key-min-256-bits

# File Upload
spring.servlet.multipart.max-file-size=50MB
```

## MongoDB Collections

### plugins
Main plugin metadata (name, description, latest version, downloads, etc.)

### plugin_versions
Version history for each plugin (changelog, VSIX file reference, dependencies)

### plugin_installations
User installation tracking (which users have which plugins installed)

## Publishing a Plugin

```bash
curl -X POST http://localhost:8087/api/plugins \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -F "metadata=@plugin-metadata.json;type=application/json" \
  -F "vsixFile=@my-plugin-1.0.0.vsix"
```

**plugin-metadata.json**:
```json
{
  "pluginId": "my-awesome-plugin",
  "name": "My Awesome Plugin",
  "version": "1.0.0",
  "description": "Does awesome things",
  "category": "Visualization",
  "keywords": ["ontology", "viewer"],
  "license": "MIT",
  "repository": "https://github.com/user/plugin",
  "entryPoint": "dist/extension.js"
}
```

## Monitoring

Health check: `http://localhost:8087/actuator/health`

## Future Enhancements

- [ ] S3 storage backend implementation
- [ ] CloudFront CDN integration
- [ ] Redis caching layer
- [ ] Code signing for verified plugins
- [ ] Sandboxed execution environment
- [ ] Plugin ratings & reviews
- [ ] Analytics & download stats
- [ ] Auto-update notifications
