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
3. `dev` profile disables the generic JWT-required filter, but the controller itself still requires a Bearer token with an `email` claim (401 otherwise), and `FreeViewOnlyInterceptor` still blocks writes for a `FREE`-plan JWT (403) unless the caller owns the workspace. The k6 script below mints its own unsigned JWT with `plan: "PRO"` so it isn't blocked — pass `-e ASSISTANT_PLAN=FREE` if you specifically want to load-test the 403 path.

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
| `TARGET_VUS` | `20` | peak concurrent virtual users |
| `RAMP_DURATION` | `15s` | time to ramp 0 -> TARGET_VUS |
| `SUSTAIN_DURATION` | `30s` | time held at TARGET_VUS |
| `THINK_TIME_SECONDS` | `0.2` | pause between iterations per VU |

Each iteration creates a brand-new session with a unique `projectId` (`<prefix>-<vu>-<iteration>`), so this measures session *creation* throughput, not repeated access to one session. Thresholds baked into the script (`p95 < 800ms`, `p99 < 1500ms`, error rate `< 1%`) make `k6 run` exit non-zero if the endpoint regresses — wire that into a CI gate once this repo has CI.

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

## Extending this for future tool endpoints

`read_context` and `run_sparql` will land next and will call `AssistantSessionService.tryConsumeRetrievalAttempt(sessionId)` internally. Once they have HTTP routes:

1. Add a `createSession()`-style function per endpoint in this file (or a new `k6-code-assistant-<tool>.js` next to it), reusing `buildUnsignedJwt`.
2. Have the setup/first iteration create one session, then hammer the tool endpoint with concurrent VUs using that same `sessionId`, to black-box-verify the retrieval-budget guard over real HTTP (the unit test in `AssistantSessionServiceTest.java` already proves the guard's concurrency contract at the Java level — see below — this would be the HTTP-level companion once the routes exist).
3. Add a `checks` assertion that a session's budget only ever reaches exactly 0 concurrent successes equal to its starting `retrievalAttemptsRemaining`, never more.

## Concurrency unit test (no infrastructure needed)

`ontology-editor/src/test/java/self/research/ontology/owlEditor/service/AssistantSessionConcurrencyTest.java` hammers an in-memory simulation of the same atomic-decrement-with-guard semantics `tryConsumeRetrievalAttempt` relies on, with dozens of real threads, and asserts the counter never goes negative and the number of successful consumes never exceeds the starting budget. It needs no Mongo/Docker/network — run it with:

```
mvn -pl ontology-editor test -Dtest=AssistantSessionConcurrencyTest
```

## Environment used to write this

Docker Desktop wasn't running in the sandbox this was authored in (its service needs an interactive/admin session to start), so the live run used a pre-existing local MongoDB 8.2 Windows service already listening on `127.0.0.1:27017` instead of `docker-compose.dev.yml`'s Mongo container, and a k6 binary downloaded directly from GitHub releases instead of the `grafana/k6` Docker image. Both are equivalent to the Docker-based setup for this endpoint (Mongo is Mongo; k6 doesn't care what it's hitting). If neither a local Mongo nor Docker is available, this test cannot run — there is no mocked/in-process fallback for the HTTP path, by design, since the point is to measure the real network+Mongo path.
