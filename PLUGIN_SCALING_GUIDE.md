# Plugin Architecture & Scaling Guide

## Architecture Overview

The OntoCode plugin system is designed for **complete independence** with the following principles:

### 🔌 Plugin Independence
- Each plugin is a standalone package with its own manifest, dependencies, and lifecycle
- Plugins can be installed/uninstalled without affecting core functionality or other plugins
- No shared state between plugins - each plugin maintains its own storage and configuration
- Zero coupling - removing a plugin leaves no trace in the codebase

### 💰 Monetization Ready
- Plugin marketplace supports both free and paid plugins
- Individual plugins can have premium features with separate licensing
- Usage-based billing can be implemented per plugin
- Plugin developers can monetize their contributions independently

### 📈 Horizontal Scalability
- Each service (plugin-service, ontology-editor, auth) can scale independently
- Containerized architecture with Kubernetes orchestration
- Auto-scaling based on CPU, memory, and custom metrics
- Zero-downtime deployments with rolling updates

---

## Service Architecture

### Services Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Load Balancer / Ingress                  │
└──────────────────────┬──────────────────┬───────────────────┘
                       │                  │
         ┌─────────────▼─────────┐   ┌────▼──────────────────┐
         │  Ontology Editor      │   │  Plugin Service       │
         │  (Port 8086)          │   │  (Port 8087)          │
         │  Replicas: 3-20       │   │  Replicas: 2-10       │
         └─────────────┬─────────┘   └────┬──────────────────┘
                       │                  │
                       └──────────┬───────┘
                                  │
                         ┌────────▼─────────┐
                         │   MongoDB        │
                         │   (GridFS)       │
                         │   StatefulSet    │
                         └──────────────────┘
```

### Service Responsibilities

**Ontology Editor (8086)**
- Core ontology editing functionality
- Project management
- File operations
- Reasoner integration
- Can scale independently based on user load

**Plugin Service (8087)**
- Plugin storage (GridFS)
- Plugin discovery and search
- Download/installation management
- Plugin versioning
- Scales based on plugin download demand

**MongoDB**
- Persistent storage for both services
- GridFS for binary plugin packages
- Replication for high availability

---

## Containerization

### Docker Images

Each service has its own Dockerfile for independent deployment:

**Plugin Service**: `ontology-plugin-service/Dockerfile`
```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
# Multi-stage build for minimal image size
# Health checks included
# Exposes port 8087
```

**Build Commands**:
```bash
# Build plugin service image
cd ontology-plugin-service
docker build -t ontocode/plugin-service:latest .

# Build ontology editor image
cd ontology-editor
docker build -t ontocode/ontology-editor:latest .
```

### Docker Compose (Local Development)

```bash
# Start all services
docker-compose up -d

# Scale plugin service independently
docker-compose up -d --scale plugin-service=3

# Scale editor service
docker-compose up -d --scale backend=5

# Stop plugin service without affecting others
docker-compose stop plugin-service

# View logs for specific service
docker-compose logs -f plugin-service
```

---

## Kubernetes Deployment

### Prerequisites

1. Kubernetes cluster (v1.24+)
2. kubectl configured
3. Helm (optional, for advanced deployments)

### Quick Deploy

```bash
# Create namespace and secrets
kubectl apply -f k8s/mongodb-statefulset.yaml

# Deploy MongoDB
kubectl wait --for=condition=ready pod -l app=mongodb -n ontocode --timeout=300s

# Deploy services
kubectl apply -f k8s/ontology-editor-deployment.yaml
kubectl apply -f k8s/plugin-service-deployment.yaml

# Deploy ingress
kubectl apply -f k8s/ingress.yaml

# Verify deployments
kubectl get pods -n ontocode
kubectl get hpa -n ontocode
```

### Horizontal Pod Autoscaling (HPA)

#### Plugin Service HPA Configuration

```yaml
minReplicas: 2
maxReplicas: 10
metrics:
  - CPU: 70% average utilization
  - Memory: 80% average utilization
```

**Scaling Behavior**:
- **Scale Up**: 100% increase every 30s (max 2 pods per cycle)
- **Scale Down**: 50% decrease every 60s with 5min stabilization
- **Triggers**: High download requests, plugin search queries

#### Ontology Editor HPA Configuration

```yaml
minReplicas: 3
maxReplicas: 20
metrics:
  - CPU: 70%
  - Memory: 80%
```

**Scaling Behavior**:
- Handles user editing sessions
- Scales based on active project count
- Reasoner operations trigger scaling

### Manual Scaling

```bash
# Scale plugin service to 5 replicas
kubectl scale deployment ontology-plugin-service --replicas=5 -n ontocode

# Scale ontology editor to 10 replicas
kubectl scale deployment ontology-editor --replicas=10 -n ontocode

# Check current scaling status
kubectl get hpa -n ontocode -w
```

### Resource Limits

**Plugin Service**:
- Request: 250m CPU, 512Mi memory
- Limit: 1 CPU, 1Gi memory

**Ontology Editor**:
- Request: 250m CPU, 512Mi memory
- Limit: 1 CPU, 2Gi memory

### Cost Optimization

```bash
# Scale down during off-peak hours (example: 2 AM - 6 AM)
kubectl scale deployment ontology-plugin-service --replicas=1 -n ontocode
kubectl scale deployment ontology-editor --replicas=2 -n ontocode

# Scale up during peak hours (example: 9 AM - 5 PM)
kubectl scale deployment ontology-plugin-service --replicas=5 -n ontocode
kubectl scale deployment ontology-editor --replicas=10 -n ontocode
```

**Automated with CronJobs**:
```yaml
# Scale down at night
apiVersion: batch/v1
kind: CronJob
metadata:
  name: scale-down
spec:
  schedule: "0 2 * * *"  # 2 AM daily
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: kubectl
            image: bitnami/kubectl
            command:
            - kubectl
            - scale
            - deployment
            - ontology-plugin-service
            - --replicas=1
            - -n
            - ontocode
```

---

## Plugin Independence Implementation

### Plugin Loader Service

**Location**: `ontology-vscode-extension/webview-src/services/pluginLoader.ts`

**Features**:
- Dynamic plugin installation from backend
- Isolated plugin lifecycle management
- No impact on core when plugins are added/removed
- LocalStorage persistence across sessions

**Key Methods**:
```typescript
pluginLoader.installPlugin(pluginId)    // Install without restart
pluginLoader.uninstallPlugin(pluginId)  // Remove without trace
pluginLoader.loadPlugin(pluginId)       // Dynamic loading
pluginLoader.isPluginInstalled(pluginId) // Check status
```

### Plugin Structure

Each plugin is a self-contained package:

```
plugins/
├── swrl-editor-plugin/
│   ├── package.json        # Manifest with dependencies
│   ├── src/
│   │   └── SWRLEditor.tsx  # Standalone component
│   ├── tsconfig.json
│   └── README.md
├── graph-view-plugin/
│   ├── package.json
│   ├── src/
│   │   └── GraphView.tsx   # Standalone component
│   ├── tsconfig.json
│   └── README.md
└── fuzzy-ontology-plugin/
    ├── package.json
    ├── src/
    └── README.md
```

### No Cross-Plugin Dependencies

**❌ Bad (Coupled)**:
```typescript
// Plugin A depends on Plugin B
import { SomethingFromPluginB } from '../plugin-b/utils';
```

**✅ Good (Independent)**:
```typescript
// Plugin A uses its own utilities
import { MyOwnUtility } from './utils';

// Or communicates through core API
fetch('/api/plugin-data');
```

### Plugin Communication (if needed)

Plugins can communicate through:
1. **Core API**: REST endpoints on backend services
2. **Events**: Pub/sub pattern through core event bus
3. **Shared Storage**: MongoDB collections with namespacing

**Example**:
```typescript
// Plugin publishes event
window.dispatchEvent(new CustomEvent('plugin:swrl:rule-executed', {
  detail: { ruleId: '123', result: 'success' }
}));

// Another plugin listens (opt-in)
window.addEventListener('plugin:swrl:rule-executed', (e) => {
  console.log('SWRL executed:', e.detail);
});
```

---

## Scaling Strategies

### 1. Service-Level Scaling

**Scenario**: Plugin downloads spike during feature launch

```bash
# Temporarily boost plugin service
kubectl scale deployment ontology-plugin-service --replicas=15 -n ontocode

# Monitor performance
kubectl top pods -n ontocode -l app=plugin-service

# Scale back when traffic normalizes
kubectl scale deployment ontology-plugin-service --replicas=3 -n ontocode
```

### 2. Database Scaling

**GridFS Performance**:
- MongoDB handles binary plugin storage
- Separate connection pools per service
- Read replicas for download-heavy workloads

```bash
# Add MongoDB read replica
kubectl scale statefulset mongodb --replicas=3 -n ontocode
```

### 3. CDN for Plugin Distribution

For high-traffic scenarios, serve plugins from CDN:

```typescript
// Plugin download from CDN instead of direct service
const pluginUrl = process.env.PLUGIN_CDN 
  ? `https://cdn.ontocode.app/plugins/${pluginId}.zip`
  : `/api/plugins/${pluginId}/download`;
```

### 4. Geo-Distribution

Deploy services in multiple regions:

```bash
# Deploy to US East
kubectl apply -f k8s/ --context=us-east-cluster

# Deploy to EU West
kubectl apply -f k8s/ --context=eu-west-cluster

# Use geo-DNS for routing
```

---

## Monitoring & Observability

### Metrics to Track

**Plugin Service**:
- Download rate (plugins/minute)
- Plugin search latency
- GridFS storage usage
- Failed installations

**Ontology Editor**:
- Active sessions
- Reasoner execution time
- File operation latency
- WebSocket connections

### Prometheus Queries

```promql
# Plugin downloads per minute
rate(plugin_downloads_total[1m])

# Service CPU usage
container_cpu_usage_seconds_total{pod=~"ontology-plugin-service.*"}

# Memory usage by service
container_memory_usage_bytes{pod=~"plugin-service.*"}

# Request latency p95
histogram_quantile(0.95, rate(http_request_duration_seconds_bucket[5m]))
```

### Alerting Rules

```yaml
# Alert if plugin service has <2 replicas during business hours
- alert: PluginServiceUnderProvisioned
  expr: kube_deployment_status_replicas{deployment="ontology-plugin-service"} < 2
  for: 5m
  annotations:
    summary: "Plugin service needs more replicas"

# Alert if download latency >2s
- alert: PluginDownloadSlow
  expr: plugin_download_duration_seconds > 2
  for: 10m
```

---

## Paid Plugin Implementation

### Backend Enhancement

Add payment verification to Plugin Service:

```java
@RestController
@RequestMapping("/api/plugins")
public class PluginController {
    
    @GetMapping("/{pluginId}/download")
    public ResponseEntity<byte[]> downloadPlugin(
        @PathVariable String pluginId,
        @RequestHeader("Authorization") String token
    ) {
        // Verify JWT token
        String userId = jwtService.getUserIdFromToken(token);
        
        // Check license/payment
        if (!licenseService.hasAccess(userId, pluginId)) {
            return ResponseEntity.status(402).build(); // Payment Required
        }
        
        // Serve plugin
        byte[] pluginData = pluginStorageService.getPlugin(pluginId);
        return ResponseEntity.ok(pluginData);
    }
}
```

### Frontend Payment Flow

```typescript
async function installPaidPlugin(pluginId: string) {
  // Check if user has purchased
  const hasLicense = await checkLicense(pluginId);
  
  if (!hasLicense) {
    // Redirect to payment
    window.location.href = `/purchase?plugin=${pluginId}`;
    return;
  }
  
  // Install as normal
  await pluginLoader.installPlugin(pluginId);
}
```

### Per-Plugin Billing

Track usage for billing:

```java
@Service
public class PluginUsageTracker {
    
    public void trackPluginUsage(String userId, String pluginId, String action) {
        UsageRecord record = new UsageRecord();
        record.setUserId(userId);
        record.setPluginId(pluginId);
        record.setAction(action);
        record.setTimestamp(Instant.now());
        
        usageRepository.save(record);
    }
}
```

---

## Disaster Recovery

### Backup Strategy

```bash
# Backup MongoDB (includes plugins)
kubectl exec -n ontocode mongodb-0 -- mongodump --out /backup

# Backup persistent volumes
kubectl get pvc -n ontocode
# Use volume snapshots

# Export Kubernetes configs
kubectl get all -n ontocode -o yaml > backup/k8s-state.yaml
```

### Restore Procedure

```bash
# Restore MongoDB
kubectl exec -n ontocode mongodb-0 -- mongorestore /backup

# Redeploy services
kubectl apply -f backup/k8s-state.yaml
```

---

## Summary

✅ **Complete Plugin Independence**
- Install/uninstall without core impact
- Dynamic loading system
- No shared dependencies

✅ **Scalability**
- Kubernetes HPA: 2-10 replicas (plugin), 3-20 replicas (editor)
- Independent service scaling
- Cost-optimized with auto-scaling policies

✅ **Containerization**
- Docker images for all services
- Multi-stage builds for efficiency
- Health checks and readiness probes

✅ **Monetization Ready**
- Payment verification endpoints
- Usage tracking per plugin
- License management system

✅ **Production Ready**
- Monitoring with Prometheus
- Logging with structured output
- Disaster recovery procedures

**Scaling Commands Quick Reference**:
```bash
# Scale up plugin service
kubectl scale deployment ontology-plugin-service --replicas=10 -n ontocode

# Scale down plugin service  
kubectl scale deployment ontology-plugin-service --replicas=2 -n ontocode

# View current status
kubectl get hpa -n ontocode -w
```
