## Reasoner Testing Guide (Protege parity)

This guide helps you verify the VS Code reasoner behaves like desktop Protégé using the provided sample ontology.

### Prerequisites
- Backend services running (plugin-service reachable at `http://localhost:8082/plugin-service`).
- Sample ontology: `test-reasoner-ontology.owl` at repo root.
- VS Code extension built/installed; open the workspace.

### Load the sample ontology
1) In the extension UI, open `test-reasoner-ontology.owl` (Project selector or Open dialog).
2) Wait for the ontology to load; confirm Entities tree is populated.

### Classification and hierarchy
1) Go to the **Reasoner** tab (main tabs row).
2) Choose **HermiT** and click **Classify**.
3) Expected: Ontology Status shows *Consistent*, entity counts > 0, and the inferred class hierarchy lists classes from the sample (e.g., `Pizza`, toppings).

### Consistency check
1) Click **Check consistency**.
2) Expected: Success toast “consistent” and status remains Consistent. Unsatisfiable section should be empty.

### Inconsistency explanation (negative test)
1) Introduce an inconsistency (e.g., add a class equivalent to `owl:Nothing` or conflicting disjoint axioms).
2) Run **Check consistency** → should report inconsistent.
3) Click **Explain** (or the Explain button in the unsatisfiable banner).
4) Expected: Explanation modal opens, listing causes (unsatisfiable classes, disjoint violations, property constraint hints) and property constraint entries show domain/range flags.

### Auto-sync
1) Enable **Auto-sync** checkbox.
2) Make an edit (e.g., add a subclass).
3) Expected: Reasoner re-runs automatically within ~2s; counts/hierarchy refresh.

### Other reasoners
Repeat Classification with **ELK**, **Pellet/Openllet**, and **Structural**. Expect:
- ELK: fast EL++ results; may omit some OWL DL inferences.
- Pellet/Openllet: full DL; slower on large ontologies.
- Structural: basic hierarchy only; consistency limited.

### REST endpoints (plugin-service)
- `POST /api/reasoner/{projectId}/classify` `{ reasonerType }` → classification payload.
- `GET /api/reasoner/{projectId}/stats?reasonerType=HERMIT` → counts/consistency flags.
- `POST /api/reasoner/{projectId}/consistency` `{ reasonerType }` → boolean consistency + unsat list.
- `POST /api/reasoner/{projectId}/explain-inconsistency` `{ reasonerType }` → causes, property violations, tips.

Use `projectId` matching the loaded file (usually the filename without extension). For the sample, try `test-reasoner-ontology`.

### Expected artifacts
- Status: Consistent; counts (non-zero) matching the sample.
- Unsatisfiable: empty for the provided OWL; populated after intentional inconsistency.
- Equivalent classes: present if defined in the sample; empty otherwise.

### Troubleshooting
- If counts show `0 / 0` or `n/a`, re-run **Classify** then **Check consistency**.
- Ensure backend reachable; see dev tools console for `api/reasoner` requests.
- For timeouts, increase `TIMEOUT` in `webview-src/services/apiClient.ts` (default 300s).