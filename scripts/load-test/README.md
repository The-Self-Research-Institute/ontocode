# Backend load & concurrency tests

This directory has two kinds of load test:

- `playwright-graph-view.js`, `playwright-pizza-parity.js` — browser-driven UI load (simulate real users clicking through the app).
- `k6-code-assistant-sessions.js` — **HTTP API** load test, no browser involved. Use this style for any new backend endpoint that needs real concurrency numbers (latency percentiles, error rate under load) rather than a UI walkthrough.
- `k6-code-assistant-group-apply.js` — HTTP API correctness-under-concurrency test for `propose`/`apply` on the assistant-edit endpoints: proves the per-project write lock actually serializes concurrent applies instead of racing (see below).

## Why k6

- Single static binary, no JVM/Docker dependency, so it doesn't need its own Maven module or a container just to load-test a Java service.
- Scripted in JS, matching the existing convention in this folder — anyone who can read the Playwright scripts can read this one.
- Built-in virtual users, ramping stages, percentile/threshold reporting and pass/fail exit codes, which is what "don't fake it, actually measure it" requires. A hand-rolled `curl`-in-a-loop script would have to reimplement most of this.
- Native binaries for Windows/macOS/Linux at https://github.com/grafana/k6/releases — no `npm install` or admin rights needed. On this machine it was fetched as a portable zip since Docker Desktop wasn't running here (see "Environment used to write this" below).

## Prerequisites

The target is `POST /api/v1/code-assistant/sessions` on the `ontology-editor` service. It only needs MongoDB (it does not touch Fuseki), so:

1. MongoDB reachable at the URI in `ontology-editor/src/main/resources/application*.properties` (or override with `MONGODB_URI`). Either:
   - `docker compose -f docker-compose.dev.yml up mongo`, or
   - any local `mongod` (see below for how this was actually run).
2. Build and start the editor service:
   ```
   mvn -pl ontology-editor -am -DskipTests package
   SPRING_PROFILES_ACTIVE=dev MONGODB_URI="mongodb://localhost:27017/<some-db>" \
     java -jar ontology-editor/target/owlEditor-1.0.0-exec.jar
   ```
   Fuseki being unavailable only breaks SPARQL/reasoner endpoints — you'll see loud `JenaHealthCheck` errors in the log at startup, they're expected and don't affect this endpoint.
3. The `dev` profile doesn't require a token, but any token that is sent is verified against `JWT_SECRET` and checked for project access, and `FreeViewOnlyInterceptor` still blocks writes for a `FREE`-plan JWT (403) unless the caller owns the workspace. The scripts sign their own JWT with the dev secret and `plan: "PRO"` — pass `-e ASSISTANT_PLAN=FREE` if you specifically want to load-test the 403 path.

## Running the load test

```
k6 run scripts/load-test/k6-code-assistant-sessions.js
```

Useful overrides (all optional, `k6 run -e NAME=value ...`):

| Env var | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8083` | editor service base URL |
| `ASSISTANT_EMAIL` | `k6-loadtest@example.com` | `email` claim in the minted JWT |
| `ASSISTANT_PLAN` | `PRO` | `plan` claim in the minted JWT |
| `TARGET_VUS` | `20` | concurrent virtual users |
| `SESSIONS_PER_VU` | `20` | sessions each VU creates (the per-user active limit is 20) |
| `THINK_TIME_SECONDS` | `0.2` | pause between iterations per VU |

`setup()` uploads one small project per VU, owned by that VU's identity. Each iteration then creates a new session on it, so this measures session *creation* throughput, not repeated access to one session. Thresholds baked into the script (`p95 < 800ms`, `p99 < 1500ms`, error rate `< 1%`) make `k6 run` exit non-zero if the endpoint regresses — wire that into a CI gate once this repo has CI.

To capture results as JSON instead of only the terminal summary:
```
k6 run --summary-export=scripts/load-test/results/k6-code-assistant-sessions-$(date +%Y%m%d-%H%M%S).json \
  scripts/load-test/k6-code-assistant-sessions.js
```

## Group-apply concurrency test (`k6-code-assistant-group-apply.js`)

The AI-assistant edit feature adds `propose` (validate one or more groups of text-range
edits against the live document) and `apply` (commit one previously-proposed group).
`apply` takes a per-project write lock (`ProjectWriteLockRegistry.runExclusive`), so this
script is not really a throughput test — it's a correctness test that happens to also
report latency: does that lock actually stop concurrent applies from racing, corrupting a
document, or double-applying the same edit?

`propose` only marks a group valid if its `originalText` matches the *actual* current
content at that line range, so this script cannot use fake content. Instead of relying on
a pre-seeded fixture project, `setup()`:

1. Uploads a small, disposable Turtle document to a fresh `projectId` via the existing
   `POST /api/ontology/upload/{projectId}` endpoint (the same multipart upload the UI
   uses) — each "slot" in the document is one `owl:Class` with a unique `rdfs:label`
   marker string (`k6-marker-<runId>-slot<N>`).
2. Polls `GET /api/ontology/status/{projectId}` until the async import reaches
   `COMPLETED`.
3. Reads the content back via `GET /api/ontology/{projectId}/content-page` and searches
   for each marker line. This sidesteps ever having to guess how the backend's
   Turtle writer formats/reorders the document on export — whatever line a marker
   actually lands on, that fetched line's exact text becomes the edit's `originalText`,
   so `propose`'s live-content check is guaranteed to pass.
4. Proposes the groups needed for each scenario against those real, known lines.

It runs three scenarios, each seeded once in `setup()` and executed once `k6 run` starts:

| Scenario | What it seeds | What it fires | What it proves |
|---|---|---|---|
| A — `disjointGroupsOneProject` | 1 project, `DISJOINT_GROUP_COUNT` groups on non-overlapping single lines | All groups' `apply` concurrently via `http.batch()` | All succeed, and every returned `newRevision` is distinct — the lock serializes commits rather than losing/racing writes |
| B — `overlappingGroupsOneCluster` | 1 project, 3 groups where 2 target the exact same line and 1 target an overlapping 2-line range | All 3 `apply` concurrently via `http.batch()` | Exactly one returns `ok:true`; the other two return `ok:false` with `errorCode` of `STALE_GROUP` or `CONFLICT` — never a second `ok:true`, which would mean a silent double-apply on the same region (the exact bug this feature exists to prevent) |
| C — `disjointProjectsInParallel` | `PARALLEL_PROJECT_COUNT` separate projects, 1 group each | One `apply` per project, one per VU, all at once | Per-apply latency should look like scenario A's, not multiply with project count — if it degrades toward a single shared number, that means applies across *different* projects are serializing through one global lock instead of per-project locks |

`http.batch()` (not multiple VUs) is used for scenarios A and B on purpose: it fires
genuinely concurrent HTTP requests from a single iteration, so every response lands back
in one JS array that a plain `check()` can reason about directly (e.g.
`new Set(newRevisions).size === newRevisions.length`). k6 VUs don't share a JS heap, so
spreading a "must be distinct across all responses" assertion across VUs would need
external aggregation (e.g. `--out json=...` and a follow-up script) — `http.batch()`
avoids that entirely while still exercising real concurrent requests against the lock.

Run it the same way as the sessions script:

```
k6 run scripts/load-test/k6-code-assistant-group-apply.js
```

Env vars (all optional):

| Env var | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8083` | editor service base URL |
| `ASSISTANT_EMAIL` | `k6-loadtest@example.com` | `email` claim in the minted JWT |
| `ASSISTANT_PLAN` | `PRO` | `plan` claim in the minted JWT (must be PRO/ENTERPRISE — `FreeViewOnlyInterceptor` blocks the seed upload and the propose/apply writes for FREE) |
| `PROJECT_PREFIX` | `k6-groupapply` | prefix for the disposable seed `projectId`s this run creates |
| `TARGET_FORMAT` | `turtle` | the `targetPath`/`content-page` format used throughout |
| `DISJOINT_GROUP_COUNT` | `5` | number of non-overlapping groups in scenario A |
| `PARALLEL_PROJECT_COUNT` | `5` | number of separate projects in scenario C |
| `IMPORT_POLL_TIMEOUT_SECONDS` | `60` | how long `setup()` waits for each seed upload's async import before giving up |
| `IMPORT_POLL_INTERVAL_SECONDS` | `1` | poll interval while waiting for import |

This needs Fuseki reachable in addition to MongoDB (unlike the plain session-creation
test) — `propose`/`apply` both read and write real document content via
`StorageManager`/`SparqlDatasetService`, which is backed by the Fuseki dataset configured by
`FUSEKI_QUERY_ENDPOINT`/`FUSEKI_UPDATE_ENDPOINT`/`FUSEKI_GSP_ENDPOINT`. See
`scripts/windows/start-hybrid.ps1` for the full local stack (Docker Mongo + Fuseki, Maven
services), or start Fuseki standalone against a local TDB2 location:

```
java -jar fuseki-docker/fuseki-server.jar --port 3030 --update --tdb2 --loc=./databases/ontocode /ontocode
```

(create `./databases/ontocode` first — this build of Fuseki doesn't create the TDB2
directory itself.)

Each `setup()` run creates 1 + 1 + `PARALLEL_PROJECT_COUNT` throwaway projects with a
handful of triples each — cheap, but they are not cleaned up automatically, so don't point
`PROJECT_PREFIX` at a shared dev Fuseki you care about keeping tidy.

`SparqlDatasetService` only gives a project its own dedicated Fuseki/TDB2 dataset once the
document crosses `ontocode.fuseki.shared-graph.max-file-mb`; smaller projects — including
every project this script seeds, which are a few hundred bytes each — share one physical
dataset as separate named graphs. That's fine for scenarios A and B (they're testing the
*application's* per-project lock, `ProjectWriteLockRegistry`, not Fuseki), but it means
scenario C's cross-project numbers on this script's own tiny seed documents are still
partly bottlenecked on Fuseki's single-writer-per-dataset transaction model, on top of
whatever the JVM/CPU is doing locally — see the real run below for what that looked like
in practice. A real multi-MB ontology would get its own dataset and avoid that entirely.

### Actual run (2026-09-23)

Run against a local, disposable stack: MongoDB (existing local Windows service on
`127.0.0.1:27017`, throwaway `k6-groupapply-test` database), Fuseki 6.1.0 run standalone
as shown above, and `ontology-editor` built from this branch
(`mvn -pl ontology-editor -am -DskipTests package`) and started with
`SPRING_PROFILES_ACTIVE=dev`, `MONGODB_URI=mongodb://localhost:27017/k6-groupapply-test`,
`FUSEKI_QUERY_ENDPOINT`/`FUSEKI_UPDATE_ENDPOINT`/`FUSEKI_GSP_ENDPOINT` pointed at the local
Fuseki. Defaults throughout (`DISJOINT_GROUP_COUNT=5`, `PARALLEL_PROJECT_COUNT=5`).

All 3 scenarios passed, 45/45 checks, no threshold failures:

| Scenario | Result | `http_req_duration` (that scenario's applies only) |
|---|---|---|
| A — 5 disjoint groups, 1 project | All 5 applied; `newRevision`s `{1,2,3,4,5}`, all distinct | avg 1.87s, min 542ms, max 3.14s (queued behind the one per-project lock — the 5th apply waits for the other 4) |
| B — 3-way overlapping cluster, 1 project | Exactly 1 `ok:true`; the other 2 both got `STALE_GROUP` (remap correctly caught the overlap before either could re-check live content) | avg 593ms, min 589ms, max 598ms (2 of these are near-instant rejections, 1 is the real commit) |
| C — 5 disjoint projects, 1 group each | All 5 applied | avg 1.49s, min 1.34s, max 1.64s |

Scenario A's own numbers already show what "queued behind one lock" looks like: request 1
finishes in ~540ms, request 5 (last in the queue) at ~3.1s — roughly `5 x ~600ms`. Scenario
C's 5 requests, one per *different* project, came back far more tightly clustered
(1.34s–1.64s) than that — nothing close to `~3s`, so the per-project lock is not forcing
different projects through one global queue. They also weren't as fast as a single
uncontended apply (Scenario B's real commit ran in ~598ms), which lines up with the shared
Fuseki dataset/JVM contention noted above rather than the application's own locking — worth
re-checking with a large enough seed document to get its own dedicated Fuseki dataset if
this ever needs to be pinned down more precisely.

No threshold breaches on any metric; see the `thresholds` block in the script for the
exact gates this run cleared.

## Assistant load suite

All scripts share `lib/assistant-k6.js` (signed dev JWTs, upload and wait, sessions, propose, apply, tools). Tokens are signed with HS256 using `JWT_SECRET` (default: the dev secret in `application-dev.properties`), because the editor now verifies every token it receives, even in the dev profile. Every script seeds its own projects in `setup()` with `ownerEmail` set, so the project access check passes for the identity that owns them. Pass a fresh `-e RUN_ID=$(date +%s)` per run so identities and project ids never collide with earlier runs.

### Running it against a throwaway stack

Keep load-test data out of your dev containers:

```
docker run -d --name ontocode-mongo-loadtest -p 127.0.0.1:27019:27017 mongo:6
docker run -d --name ontocode-fuseki-loadtest -p 3032:3030 --tmpfs "/fuseki:rw,size=4g,mode=1777" -e FUSEKI_DATASET_1=ontocode -e ADMIN_PASSWORD=admin ontocode-fuseki-local:6.1.0
python mock-llm-provider.py
```

Then start the editor from the repo root with `SPRING_PROFILES_ACTIVE=dev`, `MONGODB_URI=mongodb://127.0.0.1:27019/ontocode-loadtest`, the three `FUSEKI_*_ENDPOINT` variables pointing at port 3032, managed mode pointing at the mock (`ASSISTANT_PROVIDER=claude ASSISTANT_PROVIDER_MODEL=claude-sonnet-5 ASSISTANT_PROVIDER_API_KEY=test`), and these arguments:

```
mvn -pl ontology-editor spring-boot:run "-Dspring-boot.run.arguments=--server.port=8093 --assistant.provider.base-url=http://localhost:9099 --assistant.admission.session.max-active-per-user=100000 --assistant.admission.session.max-creates-per-minute=100000 --assistant.admission.tool.max-concurrent-per-user=64"
```

Run the suite with `-e BASE_URL=http://localhost:8093`. Remove the containers with `docker rm -f -v ontocode-mongo-loadtest ontocode-fuseki-loadtest` afterwards.

- The raised per-user limits are only for the mixed script. Project membership is written by the auth service, which isn't part of this harness, so every reader has to be the project owner, and one user would otherwise hit the per-user session and tool limits instead of the per-project ones the test is about. The per-project (8) and global (32) tool limits stay at their defaults. Run `k6-code-assistant-limits.js` against an editor on the default limits.
- A memory-backed Fuseki is used because on Docker Desktop for Windows a disk-backed TDB2 volume took 3–17 s for a one-triple write even when idle, which measures the host disk rather than the editor. Recreate the container between the 50 MB and 100 MB runs, since TDB2 does not free space for dropped graphs.
- `k6-code-assistant-provider-proxy.js` also calls the streaming endpoint (`/provider-call/stream`) and checks that events arrive without the provider's message id.

Generate fixtures first:

```
node generate-fixtures.js --only dense1,dense10,rename-10,rename-100,rename-1000,rename-5000,rename-5001
```

| Script | What it proves | Key options |
|---|---|---|
| `k6-code-assistant-sessions.js` | Session-create throughput: one identity and seeded project per VU, up to the 20-session limit each | `TARGET_VUS`, `SESSIONS_PER_VU` |
| `k6-code-assistant-limits.js` | One identity gets exactly 20 sessions, then 429 with a matching `Retry-After` | `MAX_ACTIVE`, `BURST` |
| `k6-code-assistant-group-apply.js` | Disjoint, overlapping and cross-project applies stay correct | `DISJOINT_GROUP_COUNT`, `PARALLEL_PROJECT_COUNT` |
| `k6-code-assistant-apply-chain.js` | Insert-only groups applied one after another all succeed (Apply All) | `CHAIN_GROUPS`, `TARGET_FORMAT` |
| `k6-code-assistant-mixed.js` | Readers (`read_context` range and statement, `run_sparql`) keep working while a writer applies on the same project | `FIXTURE`, `READERS`, `WRITES`, `DURATION`, `READ_P95_MS` |
| `k6-code-assistant-rename.js` | Server-derived rename at 10 to 5,000 lines, and refusal above the limit | `RENAME_FIXTURE`, `TARGET_FORMAT` |
| `k6-code-assistant-idempotency.js` | Replay of identical retries, 422 on a reused key, no duplicate sessions under concurrent retries | none |
| `k6-code-assistant-provider-proxy.js` | Managed-mode proxy latency (each VU paced under the 30 calls/min limit), the limit itself, and the streaming endpoint, against `mock-llm-provider.py` | `TARGET_VUS`, `DURATION`, `PROXY_P95_MS` |
| `k6-code-assistant-two-node.js` | Overlapping applies sent to two editor nodes: exactly one wins | `SECOND_NODE_URL`, `OVERLAPPING_GROUPS` |

### File-size scaling

Run the mixed and rename scripts once per fixture size and keep the results together:

```
k6 run -e RUN_ID=$(date +%s) -e FIXTURE=fixtures/dense-1mb.owl k6-code-assistant-mixed.js
k6 run -e RUN_ID=$(date +%s) -e FIXTURE=fixtures/dense-10mb.owl k6-code-assistant-mixed.js
k6 run -e RUN_ID=$(date +%s) -e FIXTURE=fixtures/large-50mb.owl -e DURATION=5m k6-code-assistant-mixed.js
k6 run -e RUN_ID=$(date +%s) -e FIXTURE=fixtures/xlarge-100mb.owl -e DURATION=5m k6-code-assistant-mixed.js
```

The 50 MB and 100 MB runs cross `ontocode.fuseki.shared-graph.max-file-mb` (50), so they also exercise the per-project Fuseki dataset path. Run each size once with an empty code-view cache (cold) and once again straight after (warm).

### Managed provider mode

Start the mock, then start ontology-editor with managed mode pointed at it:

```
python mock-llm-provider.py
```

```
ASSISTANT_PROVIDER=claude ASSISTANT_PROVIDER_MODEL=claude-sonnet-5 ASSISTANT_PROVIDER_API_KEY=test mvn spring-boot:run -Dspring-boot.run.arguments=--assistant.provider.base-url=http://localhost:9099
```

`MOCK_DELAY_MS` sets the simulated provider latency (default 300).

### Two nodes

Start a second ontology-editor on port 8093 against the same Mongo, Fuseki and data directory, and set `ontocode.assistant.lock.mode=mongo` on both nodes. Then run `k6-code-assistant-two-node.js` with `SECOND_NODE_URL=http://localhost:8093`. With `lock.mode=local` the same run is expected to fail, which is the point of the lease.

### Soak

Run the mixed script for 30 to 60 minutes (`-e DURATION=45m -e WRITES=200 -e WRITE_PAUSE_SECONDS=10`) and watch heap, GC, open files and the `assistant.lock.wait` / `assistant.lock.hold` timers on `/actuator/metrics`. The per-project lock and lease maps in `ProjectWriteLockRegistry` never shrink, so a soak across many projects is where growth would show.

### What to record

Record these for each run, next to the revision tested. Targets are set from the first baseline run; until then every threshold in the scripts is a placeholder.

| Operation | Metrics |
|---|---|
| Session create | p50 / p95 / p99, 429 rate (expected only in the limits script) |
| `read_context` range and statement | p50 / p95 per fixture size |
| `run_sparql` | p95, timeout rate |
| Propose | p95 per fixture size; rename propose at 1,000 and 5,000 lines |
| Apply | p95 for 1 edit and for 20 edits; rename apply at 1,000 and 5,000 lines |
| Apply All | total and per group |
| Lock | `assistant.lock.wait` p95 / max, `assistant.lock.hold` p95 / max |
| Graph reload | `graphImport` from the `[PERF] reimport` log line, p95 |
| JVM | peak heap, GC pauses |
| Errors | unexpected error rate |

## Apply patch benchmark (no infrastructure needed)

`AssistantPatchBenchmark` generates Turtle files of 1, 10, 50 and 100 MB, changes one label in the middle and times the old apply path (full parse, full reload, two-model history diff, streamed statement read) against the new one (subject index, indexed read, triple patch plan and apply). It runs against an in-memory RDF4J store, not Fuseki, and only when named:

```
mvn -pl ontology-editor test -Dtest=AssistantPatchBenchmark -Dbench.sizes=1,10,50,100
```

Results on 2026-09-25 (4 GB heap, ms, -1 = out of memory):

| MB | Triples | Full parse | Full reload | History diff | Index build | Streamed read | Indexed read | Patch plan | Patch apply |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 36,165 | 576 | 223 | 783 | 80 | 26 | 2 | 68 | 32 |
| 10 | 349,896 | 1,949 | 899 | 5,788 | 225 | 126 | 14 | 363 | 33 |
| 50 | 1,705,584 | 8,870 | 4,490 | 31,533 | 860 | 553 | 75 | 2,028 | 41 |
| 100 | 3,388,482 | 18,225 | 9,498 | -1 | 1,319 | 1,243 | 148 | 3,426 | 54 |

## Concurrency unit test (no infrastructure needed)

`ontology-editor/src/test/java/self/research/ontology/owlEditor/service/AssistantSessionConcurrencyTest.java` hammers an in-memory simulation of the same atomic-decrement-with-guard semantics `tryConsumeRetrievalAttempt` relies on, with dozens of real threads, and asserts the counter never goes negative and the number of successful consumes never exceeds the starting budget. It needs no Mongo/Docker/network — run it with:

```
mvn -pl ontology-editor test -Dtest=AssistantSessionConcurrencyTest
```

## Environment used to write this

Docker Desktop wasn't running in the sandbox this was authored in (its service needs an interactive/admin session to start), so the live run used a pre-existing local MongoDB 8.2 Windows service already listening on `127.0.0.1:27017` instead of `docker-compose.dev.yml`'s Mongo container, and a k6 binary downloaded directly from GitHub releases instead of the `grafana/k6` Docker image. Both are equivalent to the Docker-based setup for this endpoint (Mongo is Mongo; k6 doesn't care what it's hitting). If neither a local Mongo nor Docker is available, this test cannot run — there is no mocked/in-process fallback for the HTTP path, by design, since the point is to measure the real network+Mongo path.
