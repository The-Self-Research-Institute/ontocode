# Licence decisions (Phase 0 review, 2026-11-09)

Every licence claim below comes from THIRD_PARTY_NOTICES.md and the projects' own pages. It is not legal advice.

## Recorded facts

1. **OWL API** is offered under LGPL-3.0 or Apache-2.0 (dual licence). OntoCode uses it under Apache-2.0. This is OntoCode's recorded choice; the owner must confirm it.
   Evidence: OWL API project page and THIRD_PARTY_NOTICES.md.

2. **SWRLAPI 2.1.3** is declared BSD-2-Clause.
   - Its only third-party code is `org/swrlapi/factory/NaturalOrderComparator.java`. It carries a BBNT Solutions header (Cougaar Open Source License, BSD-style) plus Paour/Pool zlib-style terms: use for any purpose including commercial, the notice must stay, altered versions must be marked.
   - The sources contain no GPL text, so the "GPL-2.0-only" entry in THIRD_PARTY_NOTICES.md is a scanner misdetection.
   Evidence: `swrlapi-2.1.3-sources.jar` from Maven Central (277 Java files).
   To check it yourself: download https://repo1.maven.org/maven2/org/swrlapi/swrlapi/2.1.3/swrlapi-2.1.3-sources.jar, unzip it, and search the files for "GNU" and "GPL". Then open `org/swrlapi/factory/NaturalOrderComparator.java` and read its header.
   Status: a person who owns the licence decision should repeat this check once and sign off. Until then this is our reading, not a confirmed answer.

3. **Apache Jena and Fuseki 6.1.0** are Apache-2.0 (`jena-arq` in ontology-editor, bundled `fuseki-server.jar`).

4. **Drools 7.74.1.Final** is Apache-2.0, pulled in through `swrlapi-drools-engine`.

5. **The "FaCT++" option is really JFact 5.0.3** (LGPL-2.1). The code builds `uk.ac.manchester.cs.jfact.JFactFactory`, a Java port of FaCT++. UI labels now say "JFact (FaCT++ port)". Request ids (`fact++`, `FACTPLUSPLUS`) and the Java display name `FaCT++` are unchanged because clients, saved data and tests use them.

6. **HermiT 1.4.5.519** is LGPL-3.0, with some BSD-3 parts.

7. **owlexplanation 5.0.0** is LGPL-3.0.

8. **ELK 0.6.0** is Apache-2.0.

9. **Openllet and Pellet** are no longer available. Requests naming them still run HermiT and now log a warning.

## Open items

- LGPL replaceability wording (how users can swap the LGPL libraries in the packaged apps).
- GPL-3.0 library `org.scijava:jep:2.4.2` in `swrl.jar` (comes with SWRLAPI): remove, replace or take legal advice.
- LGPL libraries trove4j, OWL API telemetry and java-getopt: decide whether to allow them by name in `licence-policy/policy.json`.
- Fuzzy plugin: what the "threshold" setting means, and which fuzzy logic family to use.
- SHACL: which route and which library.
- JFact: keep or remove.
