# Backend load & concurrency tests

This directory has two kinds of load test:

- `playwright-graph-view.js`, `playwright-pizza-parity.js` — browser-driven UI load (simulate real users clicking through the app).
- `k6-code-assistant-sessions.js` — **HTTP API** load test, no browser involved. Use this style for any new backend endpoint that needs real concurrency numbers (latency percentiles, error rate under load) rather than a UI walkthrough.

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
