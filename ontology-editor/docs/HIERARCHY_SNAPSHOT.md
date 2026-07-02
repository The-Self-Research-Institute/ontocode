# Protégé-parity hierarchy snapshots (cloud web)

## Overview

After Fuseki import, an **OWLAPI structural reasoner** pass builds a persisted hierarchy snapshot in MongoDB (`hierarchy_snapshots`). The web Entities tab reads this index instead of live Fuseki SPARQL for:

- `GET /api/ontology/classes/top-level/{projectId}`
- `GET /api/ontology/classes/children/{projectId}`

Desktop continues to use in-memory OWLAPI warm (`ontocode.desktop.mode=true`).

## Configuration

```properties
ontocode.hierarchy.snapshot.enabled=true
ontocode.hierarchy.snapshot.legacy-sparql-fallback=false
```

## Ops

- Rebuild: `POST /api/hierarchy-worker/build/{projectId}`
- Status: `GET /api/hierarchy-worker/status/{projectId}`
- Evict + rebuild: `POST /api/ontology/ontology/hierarchy/rebuild/{projectId}`

## Infra

- Snapshot build runs on `hierarchyIndexExecutor` (1–2 threads) inside `owl-editor` JVM.
- Peak heap during build: ~3× OWL file size (same guard as desktop warm).
- API browse path: Mongo read only (no OWLAPI on request threads).

## Algorithm version

`HierarchyAlgorithmVersion.CURRENT` — bump when semantics change; old snapshots are ignored.
