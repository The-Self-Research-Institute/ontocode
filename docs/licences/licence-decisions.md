# Licence decisions

Notes on how we read the licences of our main dependencies (review of 2026-11-09). Based on THIRD_PARTY_NOTICES.md and each project's own page. Not legal advice.

1. **OWL API** has two licences, LGPL-3.0 or Apache-2.0. We use it under Apache-2.0.

2. **SWRLAPI 2.1.3** is BSD-2-Clause. One file, `org/swrlapi/factory/NaturalOrderComparator.java`, keeps its own notices: a BBNT Solutions one (Cougaar, BSD-style) and a short zlib-style one from Paour and Pool. Both allow commercial use as long as the notices stay and changed copies are marked.
   THIRD_PARTY_NOTICES.md lists GPL-2.0-only for SWRLAPI, but that is a scanner mistake. On 2026-11-13 we searched all 277 Java files in `swrlapi-2.1.3-sources.jar` (Maven Central, group `edu.stanford.swrl`) for "GNU" and "GPL" and found no licence text, only the word "signum".
   To repeat it, download the jar from https://repo1.maven.org/maven2/edu/stanford/swrl/swrlapi/2.1.3/, search it the same way, and read the header of `NaturalOrderComparator.java`.

3. **Apache Jena and Fuseki 6.1.0** are Apache-2.0 (`jena-arq` in ontology-editor and the bundled `fuseki-server.jar`).

4. **Drools 7.74.1.Final** is Apache-2.0. It comes in through `swrlapi-drools-engine`.

5. **The "FaCT++" option is really JFact 5.0.3** (LGPL-2.1), a Java port of FaCT++. The UI now says "JFact (FaCT++ port)". The request ids (`fact++`, `FACTPLUSPLUS`) and the Java display name `FaCT++` stay as they are, because clients, saved data and tests use them.

6. **HermiT 1.4.5.519** is LGPL-3.0, with some BSD-3-Clause parts.

7. **owlexplanation 5.0.0** is LGPL-3.0.

8. **ELK 0.6.0** is Apache-2.0.

9. **Openllet and Pellet** are gone. Requests that name them run HermiT and log a warning.

10. **`@secretlint/secretlint-rule-preset-recommend` 10.2.2** is MIT, but FOSSA flags it as GPL-1.0-only, which our policy denies. The flag comes from a copy of `node-forge` bundled in the package. `node-forge` is BSD-3-Clause or GPL-2.0, and we take BSD-3-Clause.
    It is only pulled in by `@vscode/vsce`, a dev dependency we use to package the VSIX. It is not in the VSIX, the desktop app or the server images (we checked the file list of the 1.2.9 VSIX). We ignore the FOSSA issue.

11. **Replacing the LGPL libraries.** HermiT, JFact, owlexplanation, trove4j (LGPL-2.1), OWL API telemetry (LGPL-3.0) and java-getopt (LGPL-2.0, pulled in by HermiT) are shipped unmodified as separate jars, and all six are allowed by name in `licence-policy/policy.json`. `licenses/lgpl/README.md` lists where the jars are in each package, where to get the source, and the steps to swap them in the Spring Boot jars, the desktop app and the Docker images. The licence texts are in the same folder.

## Open items

- `org.scijava:jep:2.4.2` (GPL-3.0) in `swrl.jar`, which comes with SWRLAPI: remove it, replace it, or get legal advice.
