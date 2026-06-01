# Upload and performance change notes (May 2026)

Handoff for **safe, low-risk** upload/dashboard performance work and **long-timeout** configuration. Use this when tuning, reverting, or extending behavior.

Older batch/GraphDB tuning is documented separately in [`PERFORMANCE_OPTIMIZATIONS.md`](../PERFORMANCE_OPTIMIZATIONS.md) at the repo root. That file may still list **30-minute** editor timeouts; this note reflects the **2-hour** defaults applied in May 2026.

## Scope

| Area | Intent | User-visible behavior |
|------|--------|------------------------|
| Large-file sanitize | Skip whole-file RDF/XML fixup and OWL API re-serialization above **50 MB** | Same successful imports; malformed large RDF/XML may rely on import fallback instead of pre-fix |
| Large-file preparse | Skip duplicate async full-file parse above **50 MB** | No early Mongo preparse metrics for large uploads; import metrics still update |
| Dashboard load | Metadata first, then parallel ontology GETs | Same data; slightly different request ordering and cold-cache contention |
| HTTP/proxy timeouts | Allow long uploads (up to **1 GB** target) | Fewer client/gateway **504**s; idle connections held longer |
| Billing cron env | Schedule billing/invitation jobs via env | Same jobs unless env overrides cron |

**Not implemented** (discussed only; no code): GraphDB server-import **405** fix, post-import query shaping beyond dashboard staging, Mongo/Stripe billing resync automation, AWS **ALB** idle timeout in repo.

---

## Shared threshold: 50 MB

Both editor shortcuts use **`50 * 1024 * 1024` bytes**. Change both together if you move the cutoff:

- `ontology-editor/.../util/OWLFormatConverter.java` — `sanitizeFileOnDisk`
- `ontology-editor/.../controller/ProjectLoadController.java` — preparse gate

---

## 1. Large-file sanitization (editor)

**File:** `ontology-editor/src/main/java/self/research/ontology/owlEditor/util/OWLFormatConverter.java`  
**Method:** `sanitizeFileOnDisk`

**Current behavior**

- All sizes: `stripBinaryPrefix` (in-place replace when a prefix is stripped).
- **≤ 50 MB:** `fixMalformedRdfXml`, `sanitizeNTriplesIRIs`, `reserializeWithOwlApi`.
- **> 50 MB:** prefix strip only; log line contains `[PERFORMANCE] Large file sanitization complete (prefix check only; ...)`.

**Why:** Whole-file read/regex and OWL API re-serialization dominated sanitize time on ~225 MB RDF/XML (~196 s in traces). GraphDB import streams the file; structural errors can still trigger OWL API fallback in import (`ProjectImportService`).

**Revert**

In the `else` branch for large files, restore the previous full path:

```java
fixMalformedRdfXml(filePath);
sanitizeNTriplesIRIs(filePath);
```

Do **not** call `reserializeWithOwlApi` on large files unless you accept high memory use.

**Verify**

- Logs: `[PERFORMANCE] Large file detected`, then either full sanitize steps or the “prefix check only” line.
- Import: `[IMPORT]` / `[TIMING]` sanitize segment duration; watch for OWL API fallback on bad XML.

**Risk if reverted:** Longer imports and higher memory on large files; may fix edge-case malformed RDF/XML before GraphDB.

---

## 2. Skip preparse on large uploads (editor)

**File:** `ontology-editor/src/main/java/self/research/ontology/owlEditor/controller/ProjectLoadController.java`  
**Context:** After `importWorkerDispatcher.dispatch` and `detectFormat`.

**Current behavior**

- **≤ 50 MB:** `preparseService.preparse(original, actualProjectId, format)` (async on `owlParsingExecutor`).
- **> 50 MB:** skip; log `Skipping preparse for large upload`.

**Why:** Preparse is a second full-file streaming parse competing with import on disk/CPU.

**Revert**

Replace the `if (Files.size(original) <= 50L * 1024 * 1024)` block with unconditional:

```java
preparseService.preparse(original, actualProjectId, format);
```

**Verify**

- Log: `Skipping preparse for large upload` absent when reverted.
- Mongo/import queue: early preparse metrics for large files return.

**Risk if reverted:** Duplicate parse work on large uploads.

---

## 3. Dashboard staged API loading (extension webview)

**Files**

- `ontology-vscode-extension/webview-src/components/Dashboard.tsx` — main dashboard init (~`apiFetchStart`).
- `ontology-vscode-extension/webview-src/components/dashboard-parts/hooks/useDashboardInit.ts` — shared init hook.

**Current behavior**

1. `GET /api/ontology/metadata/{projectId}` (await).
2. `Promise.all` for remaining endpoints (classes/top-level, properties, individuals, annotation-properties, datatypes).
3. `Dashboard.tsx` only: instance counts via separate `instanceCountsPromise` (non-blocking `.then`).

**Revert**

- Collapse back to a **single** `Promise.all` including metadata (and instance counts in the same batch if that was the prior shape).
- Remove metadata-first `await` if you want identical parallel fan-out to the old path.

**Verify**

- Browser devtools: metadata request starts before other ontology GETs.
- `[Dashboard] [PERF]` timing logs in webview console.

**Risk if reverted:** Higher parallel load on cold GraphDB/memcache after import (several ~55–60 s GETs in traces).

---

## 4. HTTP and proxy timeouts (2 hours)

Defaults target **7200 s / 7_200_000 ms** for upload-related paths. Non-upload API clients often stay at **10 minutes**.

### Editor (Tomcat / async)

**File:** `ontology-editor/src/main/resources/application.properties`

| Property | Default | Env override |
|----------|---------|--------------|
| `server.tomcat.connection-timeout` | `7200000` ms | `ONTOLOGY_HTTP_TIMEOUT_MS` |
| `server.connection-timeout` | `7200s` | `ONTOLOGY_HTTP_TIMEOUT_SECONDS` |
| `spring.mvc.async.request-timeout` | `7200000` ms | `ONTOLOGY_HTTP_TIMEOUT_MS` |

**Revert:** Restore prior values (e.g. **1800000** ms / **1800s**) in properties and compose env.

### Gateway

**Files:** `ontology-gateway/src/main/resources/application.properties`, `application-docker.properties`  
**Property:** `spring.cloud.gateway.httpclient.response-timeout=${GATEWAY_HTTP_RESPONSE_TIMEOUT:7200s}`

**Compose:** `docker-compose.yml` — `GATEWAY_HTTP_RESPONSE_TIMEOUT` on gateway service.

### Kubernetes ingress

**File:** `k8s/ingress.yaml`

- `proxy-read-timeout` / `proxy-send-timeout`: `"7200"`
- `proxy-body-size`: `"1024m"`

**Revert:** Lower timeouts and body size to match your ingress/ALB policy.

### VS Code extension

| File | What changed |
|------|----------------|
| `webview-src/services/apiClient.ts` | `UPLOAD_TIMEOUT = 7_200_000` for `/api/ontology/upload/` |
| `src/config/uploadConfig.ts` | `uploadTimeout: 120 * 60 * 1000` |
| `src/extension.ts` | Upload axios cap `7_200_000` |
| `webview-src/utils/vscodeBridge.ts` | Import status poll cap `7_200_000` (scaled by file size below cap) |

**Revert:** Reduce constants/caps together so upload, proxy, and poll limits stay aligned.

### Env template

**File:** `.env.example` — documents `GATEWAY_HTTP_RESPONSE_TIMEOUT`, `ONTOLOGY_HTTP_TIMEOUT_MS`, `ONTOLOGY_HTTP_TIMEOUT_SECONDS`.

### Outside the repo

Production **AWS ALB idle timeout** (e.g. API host) is **not** in git. If uploads still **504** while import completes in editor logs, raise ALB idle timeout to match gateway/editor (and confirm client/extension limits).

**Deploy after timeout edits:** gateway, editor, extension (and ingress/compose env as applicable).

---

## 5. Billing and invitation cron via environment

**Properties:** `ontology-auth/src/main/resources/application.properties`

| Property | Env | Default |
|----------|-----|---------|
| `billing.reminder.cron` | `BILLING_REMINDER_CRON` | `0 0 9 * * *` |
| `billing.autorenewal.cron` | `BILLING_AUTORENEWAL_CRON` | `0 0 2 * * ?` |
| `invitation.cleanup.cron` | `INVITATION_CLEANUP_CRON` | `0 0 2 * * *` |

**Code:** `InvitationCleanupService.java` — `@Scheduled(cron = "${invitation.cleanup.cron:...}")`

**Compose:** `docker-compose.yml`, `docker-compose.production.yml` pass the env vars into auth.

**Revert:** Remove env overrides and rely on property defaults, or restore hard-coded cron in Java if that was the older pattern.

**Note:** Billing **UI** renewal state comes from Stripe via API; `scripts/payment-test/payment-test.js` reads **Mongo** only — not changed by this work.

---

## Log markers (regression / before-after)

| Marker | Where |
|--------|--------|
| `[TIMING]`, `[IMPORT]`, `[PERF]`, `[MEMCACHE]`, `[SLOW_REQUEST]` | Editor import and ontology paths |
| `[ProjectLoadController] Skipping preparse for large upload` | Upload handler |
| `[PERFORMANCE] Large file sanitization complete` | Sanitize |
| `[Dashboard] [PERF]` | Webview dashboard load |

**Log files (container):** `/app/logs/` — e.g. `owl-editor-import.log`, `owl-editor-performance.log`.

**Gateway:** correlate `[REQ]` with browser Network tab on **504**; gateway may show **200** while ALB or client times out.

---

## Suggested validation after change or revert

1. **Large RDF/XML upload** (>50 MB): compare sanitize + total import time in `[TIMING]` / `[IMPORT]`.
2. **Malformed large XML** (if you re-enabled full sanitize): confirm import still succeeds or fallback path is acceptable.
3. **Dashboard open** after import: metadata and hierarchy populate; instance counts may arrive slightly later in `Dashboard.tsx`.
4. **Timeouts:** upload through gateway with file size near your cap; no premature client abort if server still working.

---

## Quick revert checklist

- [ ] `OWLFormatConverter.sanitizeFileOnDisk` — large-file `else` branch  
- [ ] `ProjectLoadController` — unconditional `preparse`  
- [ ] `Dashboard.tsx` / `useDashboardInit.ts` — parallel fetch shape  
- [ ] Editor + gateway properties and compose/k8s env  
- [ ] Extension `apiClient`, `uploadConfig`, `extension.ts`, `vscodeBridge` timeouts  
- [ ] Production ALB idle timeout (ops)  
- [ ] Redeploy affected services and rebuild/reload extension webview  

---

*Last updated: May 2026 — upload performance and timeout pass (editor, gateway, extension, ingress, auth cron env).*
