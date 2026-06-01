# OntoCode Capacity Planning Guide

## Instance Specifications

| Instance | vCPU | RAM | Cost/mo | `MAX_CONCURRENT_IMPORTS` |
|---|---|---|---|---|
| t3.large | 2 | 8 GB | ~$67 | 1 |
| t3.xlarge | 4 | 16 GB | ~$134 | 2 |
| t3.2xlarge | 8 | 32 GB | ~$268 | 3 |
| m5.xlarge (no burst) | 4 | 16 GB | ~$154 | 2 |

> **t3 vs m5**: t3 instances have burstable CPU — credits deplete under sustained load and throttle to ~20% capacity. Use m5.xlarge instead of t3.xlarge when users are editing heavily for hours at a time.

---

## Config Changes Made (This PR)

| File | Change | Why |
|---|---|---|
| `ontology-gateway/.../application.properties` | `spring.codec.max-in-memory-size`: 512MB → **64MB** | Gateway streams uploads via Netty, not codec buffer. 512MB was causing OOM at 3+ concurrent uploads |
| `docker-compose.yml` — gateway | `JAVA_OPTS -Xmx`: 1536m → **768m** | Freed ~768MB for GraphDB and owl-editor |
| `docker-compose.yml` — gateway | `memory limit`: 2g → **1500m** | -Xmx768m heap + ~300m Netty off-heap + 430m headroom; still saves 500MB from original 2g |
| `docker-compose.yml` — owl-editor | Added `ONTOCODE_IMPORT_MAX_CONCURRENT` env var | Allows tuning per-instance without code change |
| `ImportQueueManager.java` | `MAX_CONCURRENT_IMPORTS` static → `@Value` field | Reads from `ONTOCODE_IMPORT_MAX_CONCURRENT` |
| `editor/application.properties` | Added `ontocode.import.max-concurrent` | Wires env var to the queue manager |

To change concurrency when upgrading instance, only set this in `.env`:
```
ONTOCODE_IMPORT_MAX_CONCURRENT=2   # t3.xlarge
ONTOCODE_IMPORT_MAX_CONCURRENT=3   # t3.2xlarge
```

---

## How Concurrency Works

There are **two separate concurrency systems** — they are independent:

### 1. Inline Editing (SPARQL Mutations)
Add class, add annotation, edit restriction, etc. These are small SPARQL INSERT/DELETE operations.

- **Per-project lock**: Two users editing the same project are serialized. Two users editing **different projects** run in parallel.
- **Tomcat threads**: 300 max — handles 300 simultaneous HTTP requests across all users.
- **GraphDB serializes all writes** — even across projects, writes queue inside GraphDB at the SPARQL level. Each write is ~5–50ms, so throughput is high.

### 2. File Imports (OWL / TTL / RDF Upload)
Uploading and importing a `.owl` or `.ttl` file. These are long-running jobs (seconds to minutes).

- **Global queue**: Only `ONTOCODE_IMPORT_MAX_CONCURRENT` imports run at a time across all users.
- **All others wait in queue** — users see their position and estimated wait time in the UI.
- GraphDB holds an exclusive write lock during bulk import — cannot be parallelized safely.

---

## Scenario Tables

### File Import Times by File Size and Instance

These are wall-clock times from upload start to "import complete" notification.

| File Size | t3.large | t3.xlarge | t3.2xlarge |
|---|---|---|---|
| 10 MB | ~30s | ~20s | ~15s |
| 50 MB | ~2 min | ~1 min | ~45s |
| 100 MB | ~4 min | ~2 min | ~90s |
| 300 MB | ~12 min | ~6 min | ~4 min |
| 500 MB | ~20 min | ~10 min | ~7 min |
| 1 GB | ~40 min | ~20 min | ~14 min |

> Times include: upload transfer + GraphDB parse + triple indexing. Network speed affects upload phase. Indexing phase is CPU-bound.

---

### Scenario A — Mixed: Editing + Single Upload

**Setup**: Users browsing/editing ontologies. 1 user uploads a file.

| Users Editing | Upload File Size | t3.large | t3.xlarge | t3.2xlarge |
|---|---|---|---|---|
| 10 editing | 100 MB | Editing responsive. Upload takes ~4 min | Editing responsive. Upload ~2 min | Editing responsive. Upload ~90s |
| 25 editing | 100 MB | Editing may slow (CPU credits under pressure) | Editing responsive. Upload ~2 min | Editing responsive. Upload ~90s |
| 50 editing | 100 MB | **Not safe** — OOM or CPU throttle risk | Editing normal. Upload ~2.5 min | Editing responsive. Upload ~2 min |
| 10 editing | 300 MB | Editing responsive. Upload ~12 min | Editing responsive. Upload ~6 min | Editing responsive. Upload ~4 min |
| 50 editing | 300 MB | **Not safe** | Editing slows slightly. Upload ~7 min | Editing responsive. Upload ~4 min |

---

### Scenario B — Multiple Simultaneous Uploads (Queue Behavior)

**Setup**: N users each upload a file at the same time. Only `ONTOCODE_IMPORT_MAX_CONCURRENT` run at once. Others wait.

#### t3.large (MAX_CONCURRENT_IMPORTS = 1)

| Users Uploading | File Size Each | User 1 Wait | User 2 Wait | User 3 Wait | User 4 Wait | User 5 Wait |
|---|---|---|---|---|---|---|
| 2 users | 100 MB | ~4 min | ~8 min | — | — | — |
| 3 users | 100 MB | ~4 min | ~8 min | ~12 min | — | — |
| 5 users | 100 MB | ~4 min | ~8 min | ~12 min | ~16 min | ~20 min |
| 2 users | 300 MB | ~12 min | ~24 min | — | — | — |
| 5 users | 300 MB | ~12 min | ~24 min | ~36 min | ~48 min | ~60 min |
| 5 users | 50 MB | ~2 min | ~4 min | ~6 min | ~8 min | ~10 min |

#### t3.xlarge (MAX_CONCURRENT_IMPORTS = 2)

| Users Uploading | File Size Each | User 1 Wait | User 2 Wait | User 3 Wait | User 4 Wait | User 5 Wait |
|---|---|---|---|---|---|---|
| 2 users | 100 MB | ~2 min | ~2 min | — | — | — |
| 3 users | 100 MB | ~2 min | ~2 min | ~4 min | — | — |
| 5 users | 100 MB | ~2 min | ~2 min | ~4 min | ~4 min | ~6 min |
| 2 users | 300 MB | ~6 min | ~6 min | — | — | — |
| 5 users | 300 MB | ~6 min | ~6 min | ~12 min | ~12 min | ~18 min |
| 5 users | 50 MB | ~1 min | ~1 min | ~2 min | ~2 min | ~3 min |

#### t3.2xlarge (MAX_CONCURRENT_IMPORTS = 3)

| Users Uploading | File Size Each | User 1 Wait | User 2 Wait | User 3 Wait | User 4 Wait | User 5 Wait |
|---|---|---|---|---|---|---|
| 3 users | 100 MB | ~90s | ~90s | ~90s | — | — |
| 5 users | 100 MB | ~90s | ~90s | ~90s | ~3 min | ~3 min |
| 5 users | 300 MB | ~4 min | ~4 min | ~4 min | ~8 min | ~8 min |

---

### Scenario C — Pure Editing (No Uploads), Concurrent Users

**Setup**: All users browsing, editing axioms, adding classes/annotations. No file uploads.

| Concurrent Users | Workload | t3.large | t3.xlarge | t3.2xlarge |
|---|---|---|---|---|
| 10 | Light (browse only) | Fast (<200ms) | Fast | Fast |
| 10 | Mixed edit | Fast (<300ms) | Fast | Fast |
| 25 | Mixed edit | Acceptable (<500ms) | Fast (<250ms) | Fast |
| 50 | Mixed edit | **Degraded** (CPU credits depleting; 1–3s responses) | Acceptable (<500ms) | Fast (<300ms) |
| 50 | Heavy edit (all saving simultaneously) | **Not safe** | Degraded (1–2s queue at GraphDB) | Acceptable (<600ms) |
| 100 | Mixed edit | **Not supported** | Degraded | Acceptable |

---

### Scenario D — Real-World Mixed: Your Target "50 Users"

**Setup**: 50 users simultaneously — some editing, some uploading.

#### Sub-case: 10 editing + 5 uploading 100 MB each

| Instance | Editing Performance | Upload Wait (user 5) |
|---|---|---|
| t3.large | Editing slows during import (GraphDB busy) | **20 min** (queue=1, sequential) |
| t3.xlarge | Editing responsive | **6 min** (queue=2, 3 rounds) |
| t3.2xlarge | Editing responsive | **3 min** (queue=3, 2 rounds) |

#### Sub-case: 30 editing + 5 uploading 300 MB each

| Instance | Editing Performance | Upload Wait (user 5) |
|---|---|---|
| t3.large | **Unsafe** — OOM/CPU throttle risk | **60 min** |
| t3.xlarge | Slightly slower during imports (~500ms edits) | **18 min** |
| t3.2xlarge | Editing responsive | **8 min** |

#### Sub-case: 50 editing + 0 uploads (pure collaboration)

| Instance | Response Time | Risk |
|---|---|---|
| t3.large | 1–3s (CPU credits depleting after ~15 min sustained) | High — OOM risk at peak |
| t3.xlarge | 300–500ms | Low |
| t3.2xlarge | <200ms | Very low |

---

## Collaboration (Real-Time, WebSocket)

Real-time collaboration uses WebSocket (STOMP over SockJS). Each connected user holds one persistent WebSocket connection.

| Concurrent WebSocket Connections | t3.large | t3.xlarge | t3.2xlarge |
|---|---|---|---|
| 10 | Fine | Fine | Fine |
| 50 | Fine (sockets are cheap, ~2KB RAM each) | Fine | Fine |
| 100 | Fine | Fine | Fine |
| 200 | Fine | Fine | Fine |

> WebSocket connections themselves are not the bottleneck. The bottleneck is the GraphDB write when multiple users edit the **same class** simultaneously — last write wins. No merge/conflict resolution is in place currently.

---

## Memory Usage at Runtime (Actual, Not Limits)

At steady state with 50 users active:

| Service | t3.large (8 GB host) | t3.xlarge (16 GB host) |
|---|---|---|
| GraphDB | 2.2–2.8 GB | 3.5–4.5 GB |
| owl-editor | 1.5–2.5 GB | 2.0–3.0 GB |
| gateway | 300–600 MB | 300–600 MB |
| auth | 200–400 MB | 200–400 MB |
| mongodb | 300–500 MB | 400–600 MB |
| swrl | 200–400 MB | 200–400 MB |
| plugin | 150–250 MB | 150–250 MB |
| webapp | 100–150 MB | 100–150 MB |
| OS + Docker | 800 MB–1 GB | 800 MB–1 GB |
| **Total** | **5.8–8.5 GB** | **7.7–11.3 GB** |

> t3.large can OOM at peak with 50 users. t3.xlarge has comfortable headroom.

---

## Recommendations Summary

| Your Use Case | Recommended Instance |
|---|---|
| Up to 20 concurrent editors, small files (<50 MB) | t3.large — current setup is fine |
| Up to 35 concurrent editors, files up to 300 MB | **t3.xlarge** — set `ONTOCODE_IMPORT_MAX_CONCURRENT=2` |
| 50 concurrent editors, files up to 500 MB | **t3.xlarge or m5.xlarge** — set `ONTOCODE_IMPORT_MAX_CONCURRENT=2` |
| 50+ editors, large files (>500 MB), consistent speed | **t3.2xlarge** — set `ONTOCODE_IMPORT_MAX_CONCURRENT=3` |
| Sustained heavy load (no CPU burst concern) | **m5.xlarge** or **m5.2xlarge** instead of t3 |
