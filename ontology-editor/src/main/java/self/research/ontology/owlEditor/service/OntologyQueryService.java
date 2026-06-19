package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.model.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.dto.AnnotationPropertyDto;
import self.research.ontology.owlEditor.util.AnnotationValueCollector;
import self.research.ontology.owlEditor.dto.DatatypeDto;
import self.research.ontology.owlEditor.dto.IndividualDto;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.dto.PropertyDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class OntologyQueryService {

    private static final Logger log = LoggerFactory.getLogger(OntologyQueryService.class);
    
    /**
     * Thread pool for parallel SPARQL queries in classDetails.
     *
     * Sized for multi-user concurrency: each classDetails call dispatches
     * ~20 parallel futures. With 64 threads we can handle ~3 simultaneous
     * classDetails flights before queueing, which combined with @Cacheable
     * sync=true (see classDetails / classAnnotations) gives us headroom for
     * 100+ concurrent users clicking classes.
     *
     * Note: real ceiling is GraphDB, not threads — threads just feed it.
     */
    private static final ExecutorService QUERY_POOL = Executors.newFixedThreadPool(64);

    /** Fewer concurrent SPARQL calls for GO-scale graphs — avoids 45s+ query pile-ups. */
    private static final ExecutorService LARGE_QUERY_POOL = Executors.newFixedThreadPool(4);

    private Executor queryExecutorFor(String projectId) {
        return datasetService.isKnownLargeProject(projectId) ? LARGE_QUERY_POOL : QUERY_POOL;
    }

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        """;

    /**
     * Reject IRIs containing characters that would allow SPARQL injection when
     * interpolated bare inside angle-brackets (e.g. {@code <%s>}).
     * Per the SPARQL 1.1 grammar, IRIREF forbids: &lt; &gt; " { } | ^ ` \ and
     * any control character (U+0000–U+0020).
     */
    private static String safeIri(String iri) {
        if (iri == null || iri.isBlank()) {
            throw new IllegalArgumentException("IRI must not be blank");
        }
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (c == '<' || c == '>' || c == '"' || c == '{' || c == '}' ||
                    c == '|' || c == '^' || c == '`' || c == '\\' || c <= 0x20) {
                throw new IllegalArgumentException("Invalid character in IRI at position " + i + " (char=" + (int) c + ")");
            }
        }
        return iri;
    }

    private final SparqlDatasetService datasetService;
    private final TopLevelClassCacheService topLevelCacheService;

    public OntologyQueryService(SparqlDatasetService datasetService,
                                TopLevelClassCacheService topLevelCacheService) {
        this.datasetService = datasetService;
        this.topLevelCacheService = topLevelCacheService;
    }

    private String draftEntityHiddenFilter(String projectId, String entityVar) {
        String userId = SparqlQueryContext.getUserId();
        if (userId == null || userId.isBlank()) {
            return "";
        }
        return SparqlGraphUris.excludeDraftDeletedFilter(
                SparqlGraphUris.userDraftGraph(projectId, userId), entityVar);
    }

    /**
     * Get top-level classes (direct children of owl:Thing or implicit root classes).
     *
     * Three-layer read path — fastest to slowest:
     *   L1: Caffeine in-memory cache (@Cacheable) — microseconds, same JVM session.
     *   L2: MongoDB persistent cache (TopLevelClassCacheService) — milliseconds, survives restarts.
     *   L3: Fuseki SPARQL computation — seconds/minutes, used only on true cache miss.
     *
     * SPARQL computation uses a two-stage strategy:
     *   Phase 1a — IRI-only scan: pure POS index lookup, no per-row OPTIONALs or EXISTS.
     *              ORDER BY ?c (IRI) is free from the index — avoids loading all labels before LIMIT.
     *   Phase 1b — VALUES-anchored hydration: labels/descriptions/hasChildren fetched only for
     *              the N IRIs returned by Phase 1a via direct index lookups (O(N), not O(all classes)).
     *   Phase 2  — orphan supplement: classes declared owl:Class but with no named parent.
     *              Skipped for known-large or cold-TDB2 projects (p1Duration > 5s).
     *
     * Results stored in MongoDB after computation so subsequent restarts skip Fuseki entirely.
     */

    /**
     * Fast top-level count for status polling — avoids hydrating labels during long imports.
     *
     * Must agree with {@link #topLevelClasses}: top-level = explicit owl:Thing children
     * PLUS implicit roots (owl:Class with no named parent). Most real ontologies never
     * assert {@code rdfs:subClassOf owl:Thing}, so counting only explicit children made
     * the status endpoint report hierarchyReady=false forever and the UI hung on
     * "loading class tree" even though the import was complete.
     *
     * Cached in the "topLevelClasses" cache so the 2s status poll costs one query, not one
     * per poll; the cache is evicted on every mutation and import.
     */
    @Cacheable(value = "topLevelClasses", key = "#projectId + '_statusCount_' + (T(self.research.ontology.owlEditor.service.SparqlQueryContext).getUserId() ?: 'public')", sync = true)
    public int topLevelClassCount(String projectId) {
        String countQuery = PREFIXES + """
            SELECT (COUNT(DISTINCT ?c) AS ?count) WHERE {
              ?c rdfs:subClassOf <http://www.w3.org/2002/07/owl#Thing> .
              FILTER(isIRI(?c))
              %s
            }
            """.formatted(draftEntityHiddenFilter(projectId, "?c"));
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, countQuery);
            if (rs.hasNext()) {
                org.eclipse.rdf4j.query.BindingSet bs = rs.next();
                if (bs.hasBinding("count")) {
                    int explicit = Integer.parseInt(bs.getValue("count").stringValue());
                    if (explicit > 0) {
                        return explicit;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[Status] topLevelClassCount failed for {}: {}", projectId, e.getMessage());
        }

        // No explicit owl:Thing children — implicit-root ontologies (GO, MONDO, most OBO).
        // A single ASK index probe; callers only test > 0, and the full MINUS-based orphan
        // scan (7-40s on large TDB2 graphs) has no place on a 2-second status poll.
        String anyClassAsk = PREFIXES + """
            ASK {
              ?c a owl:Class .
              FILTER(isIRI(?c) && ?c != <http://www.w3.org/2002/07/owl#Thing>)
            }
            """;
        try {
            if (datasetService.execAsk(projectId, anyClassAsk)) {
                return 1;
            }
        } catch (Exception e) {
            log.debug("[Status] class existence ASK failed for {}: {}", projectId, e.getMessage());
        }
        return 0;
    }

    @Cacheable(value = "topLevelClasses", key = "#projectId + '_' + #limit + '_' + (T(self.research.ontology.owlEditor.service.SparqlQueryContext).getUserId() ?: 'public')",
               unless = "#result != null && #result.isEmpty()")
    public List<OntologyDto.TreeNode> topLevelClasses(String projectId, int limit) {
        long startTime = System.currentTimeMillis();

        // === L2: MongoDB persistent cache ===
        // Treat a cached empty list as a miss — empty was likely stored during a cold-Fuseki
        // first call (orphan scan skipped) and would block retries from ever seeing results.
        List<OntologyDto.TreeNode> mongoHit = topLevelCacheService.get(projectId, limit);
        if (mongoHit != null && !mongoHit.isEmpty()) {
            log.info("[PERF] Top-level classes served from MongoDB cache for project={} in {}ms",
                    projectId, System.currentTimeMillis() - startTime);
            return mongoHit;
        }

        // === L3: Compute from Fuseki — two-stage Phase 1 ===
        //
        // Phase 1a: pure POS index scan — returns only IRIs, no label/description/EXISTS loading.
        // ORDER BY ?c (IRI string) is satisfied by the SPO index iteration order — zero extra
        // disk reads. The old single-query approach used ORDER BY LCASE(?label) which forced
        // TDB2 to load every label from disk before LIMIT could trim anything.
        log.info("[PERF] Top-level classes (phase 1a - IRI scan) for project={}", projectId);
        String phase1aQuery = PREFIXES + """
            SELECT ?c WHERE {
              ?c rdfs:subClassOf <http://www.w3.org/2002/07/owl#Thing> .
              FILTER(isIRI(?c))
              %s
            }
            ORDER BY ?c
            LIMIT %d
            """.formatted(draftEntityHiddenFilter(projectId, "?c"), Math.max(1, limit));

        List<String> p1Iris = new java.util.ArrayList<>();
        TupleQueryResult p1aRs = datasetService.execSelect(projectId, phase1aQuery);
        while (p1aRs.hasNext()) {
            Value v = p1aRs.next().getValue("c");
            if (v != null) p1Iris.add(v.stringValue());
        }
        long p1aDuration = System.currentTimeMillis() - startTime;
        log.info("[PERF] Phase 1a: {} IRIs in {}ms", p1Iris.size(), p1aDuration);

        // Phase 1b: VALUES-anchored hydration — bounded by p1Iris.size(), NOT by total class count.
        // Jena resolves VALUES via direct hash/index lookup per IRI, so OPTIONALs and EXISTS
        // are O(N) where N = p1Iris.size() (≤ limit), not O(all owl:Class declarations).
        List<OntologyDto.TreeNode> phase1;
        if (p1Iris.isEmpty()) {
            phase1 = java.util.Collections.emptyList();
        } else {
            String valuesBlock = p1Iris.stream()
                    .map(iri -> "<" + iri + ">")
                    .collect(java.util.stream.Collectors.joining(" "));
            String phase1bQuery = PREFIXES + """
                SELECT ?c ?label ?description
                (EXISTS { ?child rdfs:subClassOf ?c . FILTER(?child != ?c && isIRI(?child)) } AS ?hasChildren)
                WHERE {
                  VALUES ?c { %s }
                  OPTIONAL { ?c rdfs:label ?label }
                  OPTIONAL { ?c rdfs:comment ?description }
                }
                """.formatted(valuesBlock);
            phase1 = mapTreeNodes(projectId, phase1bQuery, null);
        }
        long p1Duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] Top-level classes phase 1 complete: {} results in {}ms", phase1.size(), p1Duration);

        // Phase 2: supplement with orphan classes (owl:Class declarations with no named parent).
        // First, run a cheap ASK to detect if any orphans exist at all — skips the expensive
        // NOT EXISTS scan entirely for well-structured ontologies (the common case).
        Set<String> phase1Iris = phase1.stream()
                .map(OntologyDto.TreeNode::getId)
                .collect(java.util.stream.Collectors.toSet());

        String exclusionValues = phase1Iris.isEmpty() ? "" :
                "  FILTER(?c NOT IN (<" + String.join(">, <", phase1Iris) + ">))\n";

        // MINUS is used instead of FILTER NOT EXISTS. Semantically they're equivalent
        // here (both compute the set of ?c with no named-IRI rdfs:subClassOf parent).
        // Both forms are valid in Jena ARQ; we use MINUS for consistency with the
        // two-stage orphan SELECT below, where MINUS pairs well with VALUES-based
        // hydration. See https://www.w3.org/TR/sparql11-query/#neg-minus.
        // The MINUS must exclude owl:Thing just like orphanIrisQuery does — otherwise a class
        // whose only parent IS owl:Thing (e.g. an OBO root class like MONDO:0000001) incorrectly
        // looks like it has a named parent and the ASK returns false, skipping the orphan scan.
        String orphanAsk = PREFIXES + """
            ASK {
              ?c a owl:Class .
              FILTER(isIRI(?c) && ?c != <http://www.w3.org/2002/07/owl#Thing>)
            %s  MINUS {
                ?c rdfs:subClassOf ?any .
                FILTER(isIRI(?any) && ?any != <http://www.w3.org/2002/07/owl#Thing>)
              }
            }
            """.formatted(exclusionValues);

        // For very large projects (>1.5M triples), the MINUS-based orphan scan is
        // extremely expensive on TDB2 (O(classes) × index-lookup). Skip it entirely —
        // phase 1 covers the well-structured top-level classes and that is sufficient
        // for production-scale ontologies like NCBITaxon.
        // Also skip when Phase 1 itself was slow (>5s): cold TDB2 after a Fuseki restart.
        // In that scenario the COUNT timed out so the project isn't in knownLargeProjects yet,
        // but the MINUS-based orphan scan would add another 7-40 s and breach the ALB 60 s limit.
        // Phase 2 (orphan supplement) skip logic:
        // For large ontologies, the full MINUS-based orphan scan can take 7-40s and exceed
        // the ALB 60s timeout. However completely skipping Phase 2 means union members that
        // have no explicit subClassOf wrongly appear at root (or disappear entirely).
        // With per-file datasets, each project has its own isolated TDB2 so the orphan
        // scan no longer causes cross-project interference. Skip only when Fuseki is cold
        // (p1 took >10s = first query after restart) to avoid 60s timeout breaches.
        boolean knownLarge = datasetService.isKnownLargeProject(projectId);
        if (p1Duration > 10000) {
            log.info("[PERF] Skipping orphan ASK for project={} (p1Duration={}ms, cold Fuseki)",
                     projectId, p1Duration);
            List<OntologyDto.TreeNode> merged = new java.util.ArrayList<>(phase1);
            merged.sort(java.util.Comparator.comparing(n -> n.getLabel() != null ? n.getLabel().toLowerCase() : n.getId()));
            List<OntologyDto.TreeNode> result = merged.size() > limit ? merged.subList(0, limit) : merged;
            enrichWithEquivalentClasses(projectId, result);
            // Persist to MongoDB — but never persist an empty result: cold Fuseki skipped the
            // orphan scan so "empty" just means "not ready yet", not "ontology is empty".
            final List<OntologyDto.TreeNode> toStore = new java.util.ArrayList<>(result);
            final int finalLimit = limit;
            if (!toStore.isEmpty()) {
                CompletableFuture.runAsync(() -> topLevelCacheService.put(projectId, toStore, finalLimit));
            }
            return result;
        }

        boolean hasOrphans = datasetService.execAsk(projectId, orphanAsk);
        long askDuration = System.currentTimeMillis() - startTime - p1Duration;
        log.info("[PERF] Orphan ASK query: {} in {}ms", hasOrphans, askDuration);

        List<OntologyDto.TreeNode> orphans = java.util.Collections.emptyList();
        if (hasOrphans) {
            // Two-stage query.
            //
            // Stage 1 (IRI lookup): a stripped-down query that returns ONLY the
            // orphan IRIs — no labels, no descriptions, no hasChildren EXISTS, no
            // label-based ORDER BY. This is what was making the original query
            // explode on GO-scale ontologies: ARQ was materialising labels +
            // running per-row EXISTS / NOT EXISTS over the full 50k owl:Class set
            // *before* the LIMIT could trim anything (ORDER BY label forces full
            // materialisation). Now stage 1 only does the cheap candidate filter.
            //
            // Stage 2 (hydration): given the small set of orphan IRIs (≤ limit),
            // pull label / description / hasChildren in a second query whose WHERE
            // is anchored by VALUES — Jena resolves VALUES via direct index lookup
            // per IRI, so the OPTIONALs and EXISTS are bounded by `limit`, not by
            // the total class count.
            //
            // Behavior preserved: the result set is the same orphan IRIs each with
            // identical label / description / hasChildren. The outer Java sort
            // (further down at `merged.sort(...)`) re-orders by label, so the
            // stage-1 `ORDER BY ?c` only governs which IRIs are kept when total
            // orphans exceed `limit` — and it does so deterministically.
            String orphanIrisQuery = PREFIXES + """
                SELECT ?c WHERE {
                  ?c a owl:Class .
                  FILTER(isIRI(?c) && ?c != <http://www.w3.org/2002/07/owl#Thing>)
                %s  MINUS {
                    ?c rdfs:subClassOf ?super .
                    FILTER(isIRI(?super) && ?super != <http://www.w3.org/2002/07/owl#Thing> && ?super != ?c)
                  }
                  MINUS {
                    # subClassOf (NamedParent ⊓ …) — Protégé parity, e.g. Employee under Person
                    ?c rdfs:subClassOf ?anon .
                    ?anon owl:intersectionOf/rdf:rest*/rdf:first ?namedParent .
                    FILTER(isIRI(?namedParent) && ?namedParent != ?c
                           && ?namedParent != <http://www.w3.org/2002/07/owl#Thing>)
                  }
                  MINUS {
                    # Exclude union/intersection members where the containing class is named.
                    # Uses explicit blank-node traversal rather than property paths to handle
                    # all RDF serialisations (OWL/XML, RDF/XML, Turtle) consistently.
                    ?namedParent owl:equivalentClass ?ec .
                    { ?ec owl:unionOf/rdf:rest*/rdf:first ?c . }
                    UNION
                    { ?ec owl:intersectionOf/rdf:rest*/rdf:first ?c . }
                    FILTER(isIRI(?namedParent) && ?namedParent != ?c)
                  }
                  MINUS {
                    # Exclude intersection members where a named conjunct is the structural parent
                    ?c owl:equivalentClass ?ec .
                    { ?ec owl:intersectionOf/rdf:rest*/rdf:first ?namedParent . }
                    FILTER(isIRI(?namedParent) && ?namedParent != ?c
                           && ?namedParent != <http://www.w3.org/2002/07/owl#Thing>)
                  }
                  MINUS {
                    # Direct union/intersection member (no equivalentClass wrapper)
                    ?namedParent owl:unionOf/rdf:rest*/rdf:first ?c .
                    FILTER(isIRI(?namedParent) && ?namedParent != ?c)
                  }
                  MINUS {
                    # Also exclude members of anonymous unions that are the range
                    # of a subClassOf axiom (covers GCI-style union parents)
                    ?namedParent rdfs:subClassOf ?anon .
                    ?anon owl:unionOf/rdf:rest*/rdf:first ?c .
                    FILTER(isIRI(?namedParent) && isBlank(?anon) && ?namedParent != ?c)
                  }
                }
                ORDER BY ?c
                LIMIT %d
                """.formatted(exclusionValues, Math.max(1, limit));

            List<String> orphanIris = new java.util.ArrayList<>();
            TupleQueryResult irisRs = datasetService.execSelect(projectId, orphanIrisQuery);
            while (irisRs.hasNext()) {
                BindingSet sol = irisRs.next();
                String iri = resource(sol, "c");
                if (iri != null) orphanIris.add(iri);
            }

            if (!orphanIris.isEmpty()) {
                String valuesBlock = orphanIris.stream()
                        .map(iri -> "<" + iri + ">")
                        .collect(java.util.stream.Collectors.joining(" "));
                String hydrationQuery = PREFIXES + """
                    SELECT ?c ?label ?description
                    (EXISTS { ?child rdfs:subClassOf ?c . FILTER(?child != ?c && isIRI(?child)) } AS ?hasChildren)
                    WHERE {
                      VALUES ?c { %s }
                      OPTIONAL { ?c rdfs:label ?label }
                      OPTIONAL { ?c rdfs:comment ?description }
                    }
                    """.formatted(valuesBlock);
                orphans = mapTreeNodes(projectId, hydrationQuery, null);
            }
        }
        long totalDuration = System.currentTimeMillis() - startTime;
        log.info("[PERF] Top-level classes phase 2 (orphans): {} new results, total {}ms", orphans.size(), totalDuration);

        // Merge, sort by label, trim to limit, enrich with equivalent classes
        List<OntologyDto.TreeNode> merged = new java.util.ArrayList<>(phase1);
        merged.addAll(orphans);
        merged.sort(java.util.Comparator.comparing(n ->
                n.getLabel() != null ? n.getLabel().toLowerCase() : n.getId()));
        List<OntologyDto.TreeNode> result = merged.size() > limit ? merged.subList(0, limit) : merged;
        enrichWithEquivalentClasses(projectId, result);

        // Persist fully-enriched result to MongoDB (L2 cache) asynchronously — never blocks response.
        // Skip write if empty: an empty result means the orphan scan is still running or timed out;
        // persisting it would cause every future call to return empty until server restart.
        final List<OntologyDto.TreeNode> toStore = new java.util.ArrayList<>(result);
        final int finalLimit = limit;
        if (!toStore.isEmpty()) {
            CompletableFuture.runAsync(() -> topLevelCacheService.put(projectId, toStore, finalLimit));
        }

        return result;
    }


    /**
     * Get ALL classes (including children) in a single SPARQL query.
     * Used by the graph visualization to render the full class hierarchy.
     */
    @Cacheable(value = "allClasses", key = "#projectId + '_' + #limit")
    public List<OntologyDto.TreeNode> allClasses(String projectId, int limit) {
        long startTime = System.currentTimeMillis();

        String query = PREFIXES + """
            SELECT DISTINCT ?c ?label ?description ?parent
            WHERE {
              {
                ?c a owl:Class .
              } UNION {
                ?c rdfs:subClassOf ?any .
              } UNION {
                ?any rdfs:subClassOf ?c .
              } UNION {
                ?prop rdfs:domain ?c . FILTER(isIRI(?c))
              } UNION {
                ?prop rdfs:range ?c . FILTER(isIRI(?c))
              } UNION {
                ?restrict owl:someValuesFrom ?c . FILTER(isIRI(?c))
              } UNION {
                ?restrict owl:allValuesFrom ?c . FILTER(isIRI(?c))
              } UNION {
                ?restrict owl:onClass ?c . FILTER(isIRI(?c))
              }
              FILTER(isIRI(?c))
              FILTER(?c != <http://www.w3.org/2002/07/owl#Thing>)
              OPTIONAL { ?c rdfs:label ?label }
              OPTIONAL { ?c rdfs:comment ?description }
              OPTIONAL {
                {
                  ?c rdfs:subClassOf ?parent .
                  FILTER(isIRI(?parent) && ?parent != ?c)
                } UNION {
                  ?c owl:equivalentClass ?expr .
                  ?expr owl:intersectionOf/rdf:rest*/rdf:first ?parent .
                  FILTER(isIRI(?parent) && ?parent != ?c)
                } UNION {
                  ?c rdfs:subClassOf ?expr .
                  ?expr owl:intersectionOf/rdf:rest*/rdf:first ?parent .
                  FILTER(isIRI(?parent) && ?parent != ?c)
                }
              }
            }
            ORDER BY COALESCE(LCASE(?label), STR(?c))
            LIMIT %d
            """.formatted(Math.max(1, limit));

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        Map<String, OntologyDto.TreeNode> nodeMap = new LinkedHashMap<>();

        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String iri = resource(sol, "c");
            if (iri == null) continue;

            OntologyDto.TreeNode node = nodeMap.get(iri);
            if (node == null) {
                node = new OntologyDto.TreeNode();
                node.setId(iri);
                String lbl = literal(sol, "label");
                node.setLabel(lbl.isBlank() ? localName(iri) : lbl);
                node.setDescription(literal(sol, "description"));
                node.setHasChildren(true);
                nodeMap.put(iri, node);
            }

            String parentIri = resource(sol, "parent");
            if (parentIri != null) {
                List<String> parents = node.getSubClassOf();
                if (parents == null) {
                    parents = new ArrayList<>();
                    node.setSubClassOf(parents);
                }
                if (!parents.contains(parentIri)) {
                    parents.add(parentIri);
                }
                if (node.getParent() == null) {
                    node.setParent(parentIri);
                }
            }
        }

        List<OntologyDto.TreeNode> result = new ArrayList<>(nodeMap.values());
        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ [PERF] Loaded {} total classes in {}ms for project {}", result.size(), duration, projectId);

        return result;
    }

    /**
     * Get children of a specific class.
     * OPTIMIZED: Results are cached for faster subsequent access.
     * hasChildren is checked via EXISTS in the SPARQL query for accurate expand icons.
     */
    @Cacheable(value = "classChildren", key = "#projectId + '_' + #parentIri + '_' + #limit + '_' + #offset + '_' + (T(self.research.ontology.owlEditor.service.SparqlQueryContext).getUserId() ?: 'public')")
    public List<OntologyDto.TreeNode> children(String projectId, String parentIri, int limit, int offset) {
        safeIri(parentIri);
        long startTime = System.currentTimeMillis();
        
        // No ORDER BY: Fuseki can apply LIMIT early (stream-stop) instead of materializing
        // all children, sorting, then cutting — critical for large taxonomy nodes.
        // Sorting is done in Java after the fact; @Cacheable means cost is paid only once.
        // DISTINCT prevents duplicate children when the ontology has redundant
        // rdfs:subClassOf triples for the same (child, parent) pair — common in
        // OWL/RDF serialisations that materialise inferred triples alongside asserted ones.
        // FILTER isIRI excludes anonymous blank-node expressions that can appear as
        // spurious children when complex restrictions are serialised.
        // Exclude union members from asserted children.
        // GO-plus uses: ParentClass owl:equivalentClass [ owl:unionOf (A B C) ]
        // A, B, C may have explicit rdfs:subClassOf ParentClass triples (serialised inferences).
        // In the ASSERTED hierarchy these should NOT appear as children of ParentClass —
        // Protégé only shows them as children of their explicit named parents.
        String query = PREFIXES + """
            SELECT DISTINCT ?child ?label ?description ?hasChildren
            WHERE {
              {
                ?child rdfs:subClassOf <%s> .
              } UNION {
                ?child owl:equivalentClass ?expr .
                ?expr owl:intersectionOf/rdf:rest*/rdf:first <%s> .
              } UNION {
                ?child rdfs:subClassOf ?expr .
                ?expr owl:intersectionOf/rdf:rest*/rdf:first <%s> .
              }
              FILTER(isIRI(?child) && ?child != <%s>)
              %s
              # Exclude union members: <%s> owl:equivalentClass/owl:unionOf/.../child
              FILTER NOT EXISTS {
                { <%s> owl:unionOf/rdf:rest*/rdf:first ?child . }
                UNION
                { <%s> owl:equivalentClass/owl:unionOf/rdf:rest*/rdf:first ?child . }
              }
              OPTIONAL { ?child rdfs:label ?label }
              OPTIONAL { ?child rdfs:comment ?description }
              BIND(EXISTS {
                {
                  ?grandchild rdfs:subClassOf ?child .
                } UNION {
                  ?grandchild owl:equivalentClass ?gexpr .
                  ?gexpr owl:intersectionOf/rdf:rest*/rdf:first ?child .
                } UNION {
                  ?grandchild rdfs:subClassOf ?gexpr .
                  ?gexpr owl:intersectionOf/rdf:rest*/rdf:first ?child .
                }
                FILTER(?grandchild != ?child && isIRI(?grandchild))
              } AS ?hasChildren)
            }
            LIMIT %d OFFSET %d
            """.formatted(parentIri, parentIri, parentIri, parentIri,
                    draftEntityHiddenFilter(projectId, "?child"),
                    parentIri, parentIri, parentIri, Math.max(1, limit), Math.max(0, offset));

        List<OntologyDto.TreeNode> result = mapTreeNodes(projectId, query, parentIri);
        result.sort(Comparator.comparing(n -> n.getLabel() == null ? "" : n.getLabel().toLowerCase(java.util.Locale.ROOT)));
        enrichWithEquivalentClasses(projectId, result);

        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] children {} count={} time={}ms project={}", parentIri, result.size(), duration, projectId);

        return result;
    }

    /**
     * Get all properties for a project.
     * OPTIMIZED: Cached + simplified query (details loaded on-demand per property).
     */
    @Cacheable(value = "ontologyProperties", key = "#projectId + '_' + #type + '_' + #limit + '_' + #offset")
    public List<PropertyDto> properties(String projectId, String type, int limit, int offset) {
        long startTime = System.currentTimeMillis();
        String filter = switch (normalize(type)) {
            case "object" -> "FILTER(?kind = owl:ObjectProperty)";
            case "data" -> "FILTER(?kind = owl:DatatypeProperty)";
            default -> "";
        };

        // OPTIMIZED: Simplified query - load only essential fields for the tree view
        // Detailed property info (domain, range, characteristics) is loaded on-demand when selected
        String query = PREFIXES + """
            SELECT ?prop (SAMPLE(?lbl) AS ?label) (SAMPLE(?cmt) AS ?description) ?kind
                   (GROUP_CONCAT(DISTINCT STR(?super); SEPARATOR="|") AS ?superProperties)
            WHERE {
              ?prop a ?kind .
              FILTER(?kind IN (owl:ObjectProperty, owl:DatatypeProperty))
              FILTER(?prop != owl:topObjectProperty && ?prop != owl:topDataProperty)
              %s
              OPTIONAL { ?prop rdfs:label ?lbl }
              OPTIONAL { ?prop rdfs:comment ?cmt }
              OPTIONAL { ?prop rdfs:subPropertyOf ?super . FILTER(isIRI(?super) && ?super != ?prop) }
            }
            GROUP BY ?prop ?kind
            ORDER BY COALESCE(LCASE(?label), STR(?prop))
            LIMIT %d OFFSET %d
            """.formatted(filter, Math.max(1, limit), Math.max(0, offset));

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<PropertyDto> results = new ArrayList<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String iri = resource(sol, "prop");
            if (iri == null) {
                continue;
            }
            PropertyDto dto = new PropertyDto();
            dto.setId(iri);
            dto.setIri(iri);
            String label = literal(sol, "label");
            dto.setLabel(label.isBlank() ? localName(iri) : label);
            String description = literal(sol, "description");
            dto.setDescription(description);
            String kind = resource(sol, "kind");
            dto.setType(localName(kind));
            dto.setSuperProperties(splitPipe(literal(sol, "superProperties")));
            results.add(dto);
        }
        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ [PERF] Loaded {} properties in {}ms for project {}", results.size(), duration, projectId);
        return results;
    }

    /**
     * Get detailed info for a single property (domains, ranges, characteristics, etc.).
     * Called on-demand when a property is selected in the UI.
     */
    public PropertyDto propertyDetail(String projectId, String propertyIri) {
        safeIri(propertyIri);
        long startTime = System.currentTimeMillis();
        String query = PREFIXES + """
            SELECT ?prop (SAMPLE(?lbl) AS ?label) (SAMPLE(?cmt) AS ?description) ?kind
                   (GROUP_CONCAT(DISTINCT STR(?domain); SEPARATOR="|") AS ?domains)
                   (GROUP_CONCAT(DISTINCT STR(?range); SEPARATOR="|") AS ?ranges)
                   (GROUP_CONCAT(DISTINCT STR(?super); SEPARATOR="|") AS ?superProperties)
                   (GROUP_CONCAT(DISTINCT STR(?inverse); SEPARATOR="|") AS ?inverseProperties)
                   (GROUP_CONCAT(DISTINCT STR(?disjoint); SEPARATOR="|") AS ?disjointProperties)
                   (GROUP_CONCAT(DISTINCT STR(?equiv); SEPARATOR="|") AS ?equivalentProperties)
                   (GROUP_CONCAT(DISTINCT STR(?char); SEPARATOR="|") AS ?characteristics)
            WHERE {
              BIND(<%s> AS ?prop)
              ?prop a ?kind .
              FILTER(?kind IN (owl:ObjectProperty, owl:DatatypeProperty, owl:AnnotationProperty))
              OPTIONAL { ?prop rdfs:label ?lbl }
              OPTIONAL { ?prop rdfs:comment ?cmt }
              OPTIONAL { ?prop rdfs:domain ?domain . FILTER(isIRI(?domain)) }
              OPTIONAL { ?prop rdfs:range ?range . FILTER(isIRI(?range)) }
              OPTIONAL { ?prop rdfs:subPropertyOf ?super . FILTER(isIRI(?super) && ?super != ?prop) }
              OPTIONAL { { ?prop owl:inverseOf ?inverse } UNION { ?inverse owl:inverseOf ?prop } FILTER(isIRI(?inverse)) }
              OPTIONAL { ?prop owl:propertyDisjointWith ?disjoint . FILTER(isIRI(?disjoint)) }
              OPTIONAL { ?prop owl:equivalentProperty ?equiv . FILTER(isIRI(?equiv) && ?equiv != ?prop) }
              OPTIONAL {
                ?prop a ?char .
                FILTER(?char IN (
                  owl:FunctionalProperty,
                  owl:InverseFunctionalProperty,
                  owl:TransitiveProperty,
                  owl:SymmetricProperty,
                  owl:AsymmetricProperty,
                  owl:ReflexiveProperty,
                  owl:IrreflexiveProperty
                ))
              }
            }
            GROUP BY ?prop ?kind
            """.formatted(propertyIri);

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        if (rs.hasNext()) {
            BindingSet sol = rs.next();
            String iri = resource(sol, "prop");
            if (iri == null) return new PropertyDto();
            PropertyDto dto = new PropertyDto();
            dto.setId(iri);
            dto.setIri(iri);
            String label = literal(sol, "label");
            dto.setLabel(label.isBlank() ? localName(iri) : label);
            dto.setDescription(literal(sol, "description"));
            dto.setType(localName(resource(sol, "kind")));
            dto.setDomains(splitPipe(literal(sol, "domains")));
            dto.setRanges(splitPipe(literal(sol, "ranges")));

            // Fetch restriction blank nodes for range/domain and append as encoded Manchester strings.
            // Format: "display|||restrictionType|||onPropertyIri|||fillerIri|||cardinality"
            // The FILTER(isIRI(?range)) in the main query already excludes blank nodes, so we fetch them here.
            String restrQuery = PREFIXES + """
                SELECT ?axiomPred ?onProp ?onPropLabel ?filler ?fillerLabel ?restrictionType ?cardinality WHERE {
                  {
                    <%s> rdfs:range ?r .
                    BIND("range" AS ?axiomPred)
                  } UNION {
                    <%s> rdfs:domain ?r .
                    BIND("domain" AS ?axiomPred)
                  }
                  ?r a owl:Restriction ;
                     owl:onProperty ?onProp .
                  OPTIONAL { ?onProp rdfs:label ?onPropLabel }
                  {
                    { ?r owl:someValuesFrom ?filler . BIND("some" AS ?restrictionType) }
                    UNION { ?r owl:allValuesFrom ?filler . BIND("only" AS ?restrictionType) }
                    UNION { ?r owl:hasValue ?filler . BIND("value" AS ?restrictionType) }
                    UNION { ?r owl:minQualifiedCardinality ?cardinality ; owl:onClass ?filler . BIND("min" AS ?restrictionType) }
                    UNION { ?r owl:maxQualifiedCardinality ?cardinality ; owl:onClass ?filler . BIND("max" AS ?restrictionType) }
                    UNION { ?r owl:qualifiedCardinality ?cardinality ; owl:onClass ?filler . BIND("exactly" AS ?restrictionType) }
                  }
                  OPTIONAL { ?filler rdfs:label ?fillerLabel }
                  FILTER(isBlank(?r))
                }
                """.formatted(propertyIri, propertyIri);
            TupleQueryResult restrRs = datasetService.execSelect(projectId, restrQuery);
            List<String> updatedRanges = new java.util.ArrayList<>(dto.getRanges() != null ? dto.getRanges() : List.of());
            List<String> updatedDomains = new java.util.ArrayList<>(dto.getDomains() != null ? dto.getDomains() : List.of());
            while (restrRs.hasNext()) {
                BindingSet rr = restrRs.next();
                String axiomPredVal = literal(rr, "axiomPred");
                String onProp = resource(rr, "onProp");
                String onPropLabel = literal(rr, "onPropLabel");
                String filler = resource(rr, "filler");
                String fillerLabel = literal(rr, "fillerLabel");
                String restType = literal(rr, "restrictionType");
                String card = literal(rr, "cardinality");
                if (onProp == null || filler == null || restType.isBlank()) continue;
                String propDisplay = !onPropLabel.isBlank() ? onPropLabel : localName(onProp);
                String fillerDisplay = !fillerLabel.isBlank() ? fillerLabel : localName(filler);
                String display = !card.isBlank()
                    ? propDisplay + " " + restType + " " + card + " " + fillerDisplay
                    : propDisplay + " " + restType + " " + fillerDisplay;
                String encoded = display + "|||" + restType + "|||" + onProp + "|||" + filler + "|||" + card;
                if ("range".equals(axiomPredVal)) {
                    updatedRanges.add(encoded);
                } else {
                    updatedDomains.add(encoded);
                }
            }
            dto.setRanges(updatedRanges);
            dto.setDomains(updatedDomains);

            dto.setSuperProperties(splitPipe(literal(sol, "superProperties")));
            dto.setInverseProperties(splitPipe(literal(sol, "inverseProperties")));
            dto.setDisjointProperties(splitPipe(literal(sol, "disjointProperties")));
            dto.setEquivalentProperties(splitPipe(literal(sol, "equivalentProperties")));
            List<String> chars = splitPipe(literal(sol, "characteristics"));
            if (chars != null) {
                dto.setCharacteristics(chars.stream()
                    .map(charIri -> localName(charIri).replace("Property", ""))
                    .toList());
            }
            // Fetch all annotation values for this property
            String annQuery = PREFIXES + """
                SELECT ?prop ?value WHERE {
                  <%s> ?prop ?value .
                  FILTER(isLiteral(?value) || isIRI(?value))
                  {
                    ?prop a owl:AnnotationProperty .
                  } UNION {
                    VALUES ?prop {
                      rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy
                      owl:deprecated owl:versionInfo owl:backwardCompatibleWith
                      owl:incompatibleWith owl:priorVersion
                    }
                  }
                }
                """.formatted(propertyIri);
            TupleQueryResult annRs = datasetService.execSelect(projectId, annQuery);
            Map<String, List<String>> annotations = AnnotationValueCollector.newMap();
            while (annRs.hasNext()) {
                BindingSet annSol = annRs.next();
                String annProp = resource(annSol, "prop");
                String annValue = literal(annSol, "value");
                AnnotationValueCollector.add(annotations, annProp, annValue);
            }
            dto.setAnnotations(annotations);

            // Fetch property chains (owl:propertyChainAxiom) - up to 5 properties per chain
            String chainQuery = PREFIXES + """
                SELECT ?chainHead ?p0 ?p1 ?p2 ?p3 ?p4 WHERE {
                  <%s> owl:propertyChainAxiom ?chainHead .
                  ?chainHead rdf:first ?p0 .
                  OPTIONAL { ?chainHead rdf:rest ?n1 . ?n1 rdf:first ?p1 .
                    OPTIONAL { ?n1 rdf:rest ?n2 . ?n2 rdf:first ?p2 .
                      OPTIONAL { ?n2 rdf:rest ?n3 . ?n3 rdf:first ?p3 .
                        OPTIONAL { ?n3 rdf:rest ?n4 . ?n4 rdf:first ?p4 . }
                      }
                    }
                  }
                }
                """.formatted(propertyIri);
            TupleQueryResult chainRs = datasetService.execSelect(projectId, chainQuery);
            java.util.List<String> propertyChains = new java.util.ArrayList<>();
            while (chainRs.hasNext()) {
                BindingSet cs = chainRs.next();
                java.util.List<String> parts = new java.util.ArrayList<>();
                for (String var : java.util.List.of("p0", "p1", "p2", "p3", "p4")) {
                    String val = resource(cs, var);
                    if (val != null && !val.isBlank()) parts.add(val);
                    else break;
                }
                if (parts.size() >= 2) propertyChains.add(String.join(" o ", parts));
            }
            dto.setPropertyChains(propertyChains);

            long duration = System.currentTimeMillis() - startTime;
            log.info("[PERF] propertyDetail for {} completed in {}ms project={}", localName(propertyIri), duration, projectId);
            return dto;
        }
        long duration = System.currentTimeMillis() - startTime;
        log.warn("[PERF] propertyDetail for {} returned empty in {}ms project={}", propertyIri, duration, projectId);
        return new PropertyDto();
    }

    /**
     * Get individuals for a project.
     * OPTIMIZED: Cached for repeated access.
     */
    @Cacheable(value = "ontologyIndividuals", key = "#projectId + '_' + #limit + '_' + #offset")
    public List<IndividualDto> individuals(String projectId, int limit, int offset) {
        long startTime = System.currentTimeMillis();
        String query = PREFIXES + """
            SELECT ?ind (SAMPLE(?lbl) AS ?label) (SAMPLE(?cmt) AS ?description)
                   (GROUP_CONCAT(DISTINCT ?type; SEPARATOR="|") AS ?types)
            WHERE {
              ?ind a owl:NamedIndividual .
              OPTIONAL { ?ind rdfs:label ?lbl }
              OPTIONAL { ?ind rdfs:comment ?cmt }
              OPTIONAL {
                ?ind a ?type .
                FILTER(?type != owl:NamedIndividual && isIRI(?type))
              }
            }
            GROUP BY ?ind
            ORDER BY COALESCE(LCASE(?label), STR(?ind))
            LIMIT %d OFFSET %d
            """.formatted(Math.max(1, limit), Math.max(0, offset));

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<IndividualDto> individuals = new ArrayList<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String iri = resource(sol, "ind");
            if (iri == null) {
                continue;
            }
            IndividualDto dto = new IndividualDto();
            dto.setId(iri);
            dto.setIri(iri);
            String label = literal(sol, "label");
            dto.setLabel(label.isBlank() ? localName(iri) : label);
            String description = literal(sol, "description");
            dto.setDescription(description);
            dto.setTypes(splitPipe(literal(sol, "types")));
            individuals.add(dto);
        }
        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] individuals loaded {} in {}ms project={}", individuals.size(), duration, projectId);
        return individuals;
    }

    @Cacheable(value = "individualCount", key = "#projectId")
    public long individualCount(String projectId) {
        long startTime = System.currentTimeMillis();
        String query = PREFIXES + """
            SELECT (COUNT(DISTINCT ?ind) AS ?count)
            WHERE { ?ind a owl:NamedIndividual . }
            """;
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        long count = 0;
        if (rs.hasNext()) {
            BindingSet sol = rs.next();
            if (sol.hasBinding("count")) {
                Value countValue = sol.getValue("count");
                if (countValue.isLiteral()) {
                    count = Long.parseLong(countValue.stringValue());
                }
            }
        }
        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] individualCount={} in {}ms project={}", count, duration, projectId);
        return count;
    }

    public List<AnnotationPropertyDto> annotationProperties(String projectId, int limit, int offset) {
        long startTime = System.currentTimeMillis();
        String query = PREFIXES + """
            SELECT DISTINCT ?prop (SAMPLE(?lbl) AS ?label) (SAMPLE(?cmt) AS ?description)
                   (GROUP_CONCAT(DISTINCT STR(?super); SEPARATOR="|") AS ?superProperties)
            WHERE {
              ?prop a owl:AnnotationProperty .
              FILTER(!isBlank(?prop))
              OPTIONAL { ?prop rdfs:label ?lbl }
              OPTIONAL { ?prop rdfs:comment ?cmt }
              OPTIONAL { ?prop rdfs:subPropertyOf ?super . FILTER(isIRI(?super) && ?super != ?prop) }
            }
            GROUP BY ?prop
            ORDER BY COALESCE(LCASE(?label), STR(?prop))
            LIMIT %d OFFSET %d
            """.formatted(Math.max(1, limit), Math.max(0, offset));

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<AnnotationPropertyDto> props = new ArrayList<>();
        int count = 0;
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            count++;
            String iri = resource(sol, "prop");
            if (iri == null) {
                continue;
            }
            AnnotationPropertyDto dto = new AnnotationPropertyDto();
            dto.setId(iri);
            dto.setIri(iri);
            String label = literal(sol, "label");
            dto.setLabel(label.isBlank() ? localName(iri) : label);
            String description = literal(sol, "description");
            dto.setDescription(description);
            dto.setSuperProperties(splitPipe(literal(sol, "superProperties")));
            props.add(dto);
        }

        Map<String, Map<String, List<String>>> annotationsByProperty = loadAnnotationPropertyAnnotations(projectId);
        for (AnnotationPropertyDto dto : props) {
            Map<String, List<String>> annotations = new LinkedHashMap<>(
                    annotationsByProperty.getOrDefault(dto.getId(), Map.of()));
            if (dto.getLabel() != null && !dto.getLabel().isBlank()) {
                annotations.computeIfAbsent("http://www.w3.org/2000/01/rdf-schema#label", ignored -> new ArrayList<>())
                        .add(dto.getLabel());
            }
            if (dto.getDescription() != null && !dto.getDescription().isBlank()) {
                annotations.computeIfAbsent("http://www.w3.org/2000/01/rdf-schema#comment", ignored -> new ArrayList<>())
                        .add(dto.getDescription());
            }
            dto.setAnnotations(annotations);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] annotationProperties loaded {} (rows={}) in {}ms project={}", props.size(), count, duration, projectId);
        return props;
    }

    private Map<String, Map<String, List<String>>> loadAnnotationPropertyAnnotations(String projectId) {
        String query = PREFIXES + """
            SELECT ?entity ?annProp ?value WHERE {
              ?entity a owl:AnnotationProperty .
              ?entity ?annProp ?value .
              FILTER(isLiteral(?value))
              {
                ?annProp a owl:AnnotationProperty .
              } UNION {
                VALUES ?annProp { rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy }
              }
            }
            """;
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        Map<String, Map<String, List<String>>> annotationsByProperty = new LinkedHashMap<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String entityIri = resource(sol, "entity");
            String annProp = resource(sol, "annProp");
            String value = literal(sol, "value");
            if (entityIri == null || annProp == null || value.isBlank()) {
                continue;
            }
            Map<String, List<String>> entityAnnotations = annotationsByProperty
                    .computeIfAbsent(entityIri, ignored -> AnnotationValueCollector.newMap());
            AnnotationValueCollector.add(entityAnnotations, annProp, value);
        }
        return annotationsByProperty;
    }

    public List<Map<String, String>> annotationPropertyUsage(String projectId, String propertyIri) {
        safeIri(propertyIri);
        List<Map<String, String>> usages = new ArrayList<>();
        String propertyLabel = localName(propertyIri);

        Map<String, String> declaration = new LinkedHashMap<>();
        declaration.put("type", "declaration");
        declaration.put("subject", propertyIri);
        declaration.put("subjectLabel", propertyLabel);
        declaration.put("context", "AnnotationProperty: '" + propertyLabel + "'");
        usages.add(declaration);

        String selfQuery = PREFIXES + """
            SELECT ?annProp ?value WHERE {
              <%s> ?annProp ?value .
              FILTER(isLiteral(?value))
              {
                ?annProp a owl:AnnotationProperty .
              } UNION {
                VALUES ?annProp { rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy }
              }
            }
            """.formatted(propertyIri);
        TupleQueryResult selfRs = datasetService.execSelect(projectId, selfQuery);
        while (selfRs.hasNext()) {
            BindingSet sol = selfRs.next();
            String annProp = resource(sol, "annProp");
            String value = literal(sol, "value");
            if (annProp == null || value.isBlank()) {
                continue;
            }
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "annotation");
            usage.put("subject", propertyIri);
            usage.put("subjectLabel", propertyLabel);
            usage.put("predicate", annProp);
            usage.put("value", value);
            usage.put("context", "'" + propertyLabel + "' " + localName(annProp) + " \"" + value + "\"");
            usages.add(usage);
        }

        String query = PREFIXES + """
            SELECT ?subject ?subjectLabel ?value
            WHERE {
              ?subject <%s> ?value .
              FILTER(?subject != <%s>)
              OPTIONAL { ?subject rdfs:label ?subjectLabel }
            }
            ORDER BY STR(?subject)
            LIMIT 1000
            """.formatted(propertyIri, propertyIri);

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String subjectIri = resource(sol, "subject");
            if (subjectIri == null) {
                continue;
            }
            String subjectLabel = literal(sol, "subjectLabel");
            String value = sol.hasBinding("value") ? sol.getValue("value").toString() : "";
            String displaySubject = subjectLabel.isBlank() ? localName(subjectIri) : subjectLabel;

            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "annotation");
            usage.put("subject", subjectIri);
            usage.put("subjectLabel", displaySubject);
            usage.put("predicate", propertyIri);
            usage.put("value", value);
            usage.put("context", displaySubject + " '" + propertyLabel + "' " + value);
            usages.add(usage);
        }
        return usages;
    }

    public List<Map<String, String>> datatypeUsage(String projectId, String datatypeIri) {
        safeIri(datatypeIri);
        List<Map<String, String>> usages = new ArrayList<>();

        // 1. Find data properties with this range
        String rangeQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?label WHERE {
              ?prop rdfs:range <%s> .
              OPTIONAL { ?prop rdfs:label ?label }
            }
            """.formatted(datatypeIri);
        TupleQueryResult ranges = datasetService.execSelect(projectId, rangeQuery);
        while (ranges.hasNext()) {
            BindingSet sol = ranges.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "range");
            String propIri = resource(sol, "prop");
            if (propIri != null) {
                usage.put("subject", propIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(propIri));
                usage.put("context", "Range of data property");
                usages.add(usage);
            }
        }
        
        // 2. Find restrictions using this datatype
        String restrictionQuery = PREFIXES + """
            SELECT DISTINCT ?class ?label WHERE {
              ?class rdfs:subClassOf|owl:equivalentClass ?restriction .
              ?restriction owl:onDataRange|owl:someValuesFrom|owl:allValuesFrom <%s> .
              FILTER(isIRI(?class))
              OPTIONAL { ?class rdfs:label ?label }
            }
            """.formatted(datatypeIri);
        TupleQueryResult restrictions = datasetService.execSelect(projectId, restrictionQuery);
        while (restrictions.hasNext()) {
            BindingSet sol = restrictions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "restriction");
            String classIri = resource(sol, "class");
            if (classIri != null) {
                usage.put("subject", classIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(classIri));
                usage.put("context", "Used in data restriction");
                usages.add(usage);
            }
        }

        return usages;
    }

    public List<Map<String, String>> individualUsage(String projectId, String individualIri) {
        safeIri(individualIri);
        List<Map<String, String>> usages = new ArrayList<>();

        // 1. Find object property assertions where this is the object
        String assertionQuery = PREFIXES + """
            SELECT DISTINCT ?subject ?prop ?label WHERE {
              ?subject ?prop <%s> .
              ?prop a owl:ObjectProperty .
              OPTIONAL { ?subject rdfs:label ?label }
            }
            """.formatted(individualIri);
        TupleQueryResult assertions = datasetService.execSelect(projectId, assertionQuery);
        while (assertions.hasNext()) {
            BindingSet sol = assertions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "assertion");
            String subjectIri = resource(sol, "subject");
            String propIri = resource(sol, "prop");
            if (subjectIri != null) {
                usage.put("subject", subjectIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(subjectIri));
                usage.put("predicate", propIri);
                usage.put("context", "Object of " + localName(propIri));
                usages.add(usage);
            }
        }
        
        // 2. Find SameIndividual/DifferentIndividuals
        String sameDiffQuery = PREFIXES + """
            SELECT DISTINCT ?other ?type ?label WHERE {
              {
                <%s> owl:sameAs ?other .
                BIND("same" AS ?type)
              } UNION {
                ?other owl:sameAs <%s> .
                BIND("same" AS ?type)
              } UNION {
                <%s> owl:differentFrom ?other .
                BIND("different" AS ?type)
              } UNION {
                ?other owl:differentFrom <%s> .
                BIND("different" AS ?type)
              }
              OPTIONAL { ?other rdfs:label ?label }
            }
            """.formatted(individualIri, individualIri, individualIri, individualIri);
        TupleQueryResult sameDiffs = datasetService.execSelect(projectId, sameDiffQuery);
        while (sameDiffs.hasNext()) {
            BindingSet sol = sameDiffs.next();
            Map<String, String> usage = new LinkedHashMap<>();
            String type = sol.hasBinding("type") ? literal(sol, "type") : "unknown";
            usage.put("type", type);
            String otherIri = resource(sol, "other");
            if (otherIri != null) {
                usage.put("subject", otherIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(otherIri));
                usage.put("context", type.equals("same") ? "SameIndividualAs" : "DifferentIndividualFrom");
                usages.add(usage);
            }
        }

        return usages;
    }

    public List<DatatypeDto> datatypes(String projectId, int limit, int offset) {
        // First get datatypes declared in the ontology
        String query = PREFIXES + """
            SELECT DISTINCT ?dt
            WHERE {
              ?dt a rdfs:Datatype .
              FILTER(isIRI(?dt))
            }
            ORDER BY STR(?dt)
            """;

        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        Set<String> datatypes = new LinkedHashSet<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String iri = resource(sol, "dt");
            if (iri != null) {
                datatypes.add(iri);
            }
        }

        // Add standard OWL 2 datatypes
        datatypes.add("http://www.w3.org/2002/07/owl#rational");
        datatypes.add("http://www.w3.org/2002/07/owl#real");
        
        // Add standard RDF datatypes
        datatypes.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#langString");
        datatypes.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#PlainLiteral");
        datatypes.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral");
        
        // Add standard RDFS datatypes
        datatypes.add("http://www.w3.org/2000/01/rdf-schema#Literal");
        
        // Add comprehensive XSD datatypes
        String[] xsdTypes = {
            "anyURI", "base64Binary", "boolean", "byte", "date", "dateTime", "dateTimeStamp",
            "decimal", "double", "float", "hexBinary", "int", "integer", "language",
            "long", "Name", "NCName", "negativeInteger", "NMTOKEN", "nonNegativeInteger",
            "nonPositiveInteger", "normalizedString", "positiveInteger", "short", "string",
            "time", "token", "unsignedByte", "unsignedInt", "unsignedLong", "unsignedShort"
        };
        for (String type : xsdTypes) {
            datatypes.add("http://www.w3.org/2001/XMLSchema#" + type);
        }

        // Convert to DTOs with pagination
        List<DatatypeDto> result = new ArrayList<>();
        int index = 0;
        for (String iri : datatypes) {
            if (index >= offset && result.size() < limit) {
                DatatypeDto dto = new DatatypeDto();
                dto.setId(iri);
                dto.setIri(iri);
                dto.setLabel(localName(iri));
                result.add(dto);
            }
            index++;
            if (result.size() >= limit) break;
        }
        
        return result;
    }

    private List<OntologyDto.TreeNode> mapTreeNodes(String projectId, String query, String parentIri) {
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        // Use LinkedHashMap to deduplicate by IRI while preserving query order.
        // Multiple rows for the same IRI arise when a class has several rdfs:label annotations;
        // we keep the first (non-blank) label encountered and merge hasChildren truthfully.
        Map<String, OntologyDto.TreeNode> seen = new java.util.LinkedHashMap<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String iri = resource(sol, parentIri == null ? "c" : "child");
            if (iri == null) {
                continue;
            }

            if (seen.containsKey(iri)) {
                // Duplicate row (extra label/description) — merge hasChildren only
                if (sol.hasBinding("hasChildren")) {
                    Value hcv = sol.getValue("hasChildren");
                    if (hcv != null && hcv.isLiteral() && Boolean.parseBoolean(hcv.stringValue())) {
                        seen.get(iri).setHasChildren(true);
                    }
                }
                continue;
            }

            OntologyDto.TreeNode node = new OntologyDto.TreeNode();
            node.setId(iri);
            String label = literal(sol, "label");
            node.setLabel(label.isBlank() ? localName(iri) : label);
            String description = literal(sol, "description");
            node.setDescription(description);
            if (sol.hasBinding("hasChildren")) {
                Value hasChildrenValue = sol.getValue("hasChildren");
                if (hasChildrenValue.isLiteral()) {
                    node.setHasChildren(Boolean.parseBoolean(hasChildrenValue.stringValue()));
                }
            }
            node.setParent(parentIri);
            seen.put(iri, node);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * Enrich a list of tree nodes with their owl:equivalentClass partners.
     * Runs one SPARQL query for the entire batch using a VALUES clause.
     * Nodes with no equivalences are left unchanged.
     */
    private void enrichWithEquivalentClasses(String projectId, List<OntologyDto.TreeNode> nodes) {
        if (nodes.isEmpty()) return;

        String values = nodes.stream()
                .map(n -> "(<" + n.getId() + ">)")
                .collect(java.util.stream.Collectors.joining(" "));

        String q = PREFIXES + """
            SELECT DISTINCT ?cls ?equiv ?equivLabel WHERE {
              VALUES (?cls) { %s }
              {
                ?cls owl:equivalentClass ?equiv .
              } UNION {
                ?equiv owl:equivalentClass ?cls .
              }
              FILTER(?equiv != ?cls)
              OPTIONAL { ?equiv rdfs:label ?iriLabel }
              OPTIONAL {
                ?equiv owl:intersectionOf ?intersectionList .
                BIND("defined intersection" AS ?intersectionLabel)
              }
              OPTIONAL {
                ?equiv owl:unionOf ?unionList .
                BIND("defined union" AS ?unionLabel)
              }
              OPTIONAL {
                ?equiv a owl:Restriction .
                BIND("defined restriction" AS ?restrictionLabel)
              }
              OPTIONAL {
                ?equiv owl:complementOf ?complement .
                BIND("defined complement" AS ?complementLabel)
              }
              OPTIONAL {
                ?equiv owl:oneOf ?oneOfList .
                BIND("defined enumeration" AS ?oneOfLabel)
              }
              BIND(COALESCE(?iriLabel, ?intersectionLabel, ?unionLabel, ?restrictionLabel, ?complementLabel, ?oneOfLabel, "defined expression") AS ?equivLabel)
            }
            """.formatted(values);

        TupleQueryResult rs = datasetService.execSelect(projectId, q);
        // Group results by class IRI
        Map<String, List<Map<String, String>>> byClass = new java.util.LinkedHashMap<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String cls = resource(sol, "cls");
            String equiv = resourceOrBlank(sol, "equiv");
            if (cls == null || equiv == null) continue;
            String equivLabel = literal(sol, "equivLabel");
            if (equivLabel.isBlank()) {
                equivLabel = equiv.startsWith("_:") ? "defined expression" : localName(equiv);
            }
            Map<String, String> entry = new java.util.LinkedHashMap<>();
            entry.put("iri", equiv);
            entry.put("label", equivLabel);
            byClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(entry);
        }

        // Attach results back to nodes
        Map<String, OntologyDto.TreeNode> nodeMap = nodes.stream()
                .collect(java.util.stream.Collectors.toMap(OntologyDto.TreeNode::getId, n -> n, (a, b) -> a));
        byClass.forEach((cls, equivList) -> {
            OntologyDto.TreeNode node = nodeMap.get(cls);
            if (node != null) node.setEquivalentClasses(equivList);
        });
    }

    /**
     * Render a Manchester-style class expression for a blank node (restriction, and/or/not, etc.).
     */
    private String describeBlankNodeManchester(String projectId, String blankNodeId) {
        if (blankNodeId == null || blankNodeId.isBlank()) return null;
        if (blankNodeId.startsWith("http://") || blankNodeId.startsWith("https://") || blankNodeId.startsWith("urn:")) {
            return null;
        }

        String restriction = describeBlankNodeRestriction(projectId, blankNodeId);
        if (restriction != null && !restriction.isBlank()) return restriction;

        List<String> intersection = describeBlankNodeMemberList(projectId, blankNodeId, "intersectionOf");
        if (!intersection.isEmpty()) return String.join(" and ", intersection);

        List<String> union = describeBlankNodeMemberList(projectId, blankNodeId, "unionOf");
        if (!union.isEmpty()) return String.join(" or ", union);

        String complement = describeBlankNodeComplement(projectId, blankNodeId);
        if (complement != null && !complement.isBlank()) return "not " + complement;

        List<String> oneOf = describeBlankNodeMemberList(projectId, blankNodeId, "oneOf");
        if (!oneOf.isEmpty()) return "{" + String.join(", ", oneOf) + "}";

        return null;
    }

    private String blankNodeFilterClause(String var, String blankNodeId) {
        String nodeKey = blankNodeId.startsWith("_:") ? blankNodeId.substring(2) : blankNodeId;
        String escaped = nodeKey.replace("\\", "\\\\").replace("\"", "\\\"");
        return String.format("FILTER(isBlank(%s) && STR(%s) = \"%s\")", var, var, escaped);
    }

    private String describeBlankNodeRestriction(String projectId, String blankNodeId) {
        String query = PREFIXES + """
            SELECT ?prop ?propLabel ?restrictionType ?filler ?fillerLabel ?card WHERE {
              ?bnode a owl:Restriction ;
                     owl:onProperty ?prop .
              %s
              OPTIONAL { ?prop rdfs:label ?propLabel }
              OPTIONAL {
                { ?bnode owl:someValuesFrom ?filler . BIND("some" AS ?restrictionType) }
                UNION { ?bnode owl:allValuesFrom ?filler . BIND("only" AS ?restrictionType) }
                UNION { ?bnode owl:hasValue ?filler . BIND("value" AS ?restrictionType) }
                UNION { ?bnode owl:hasSelf true . BIND("Self" AS ?filler) . BIND("some" AS ?restrictionType) }
                UNION { ?bnode owl:minQualifiedCardinality ?card . BIND("min" AS ?restrictionType) }
                UNION { ?bnode owl:maxQualifiedCardinality ?card . BIND("max" AS ?restrictionType) }
                UNION { ?bnode owl:qualifiedCardinality ?card . BIND("exactly" AS ?restrictionType) }
                UNION {
                  ?bnode owl:minCardinality ?card .
                  FILTER NOT EXISTS { ?bnode owl:minQualifiedCardinality ?any }
                  BIND("min" AS ?restrictionType)
                  BIND(owl:Thing AS ?filler)
                }
                UNION {
                  ?bnode owl:maxCardinality ?card .
                  FILTER NOT EXISTS { ?bnode owl:maxQualifiedCardinality ?any }
                  BIND("max" AS ?restrictionType)
                  BIND(owl:Thing AS ?filler)
                }
                UNION {
                  ?bnode owl:cardinality ?card .
                  FILTER NOT EXISTS { ?bnode owl:qualifiedCardinality ?any }
                  BIND("exactly" AS ?restrictionType)
                  BIND(owl:Thing AS ?filler)
                }
              }
              OPTIONAL { ?bnode owl:onClass ?filler }
              OPTIONAL { ?bnode owl:onDataRange ?filler }
              OPTIONAL { ?filler rdfs:label ?fillerLabel }
              FILTER(BOUND(?restrictionType))
            }
            LIMIT 1
            """.formatted(blankNodeFilterClause("?bnode", blankNodeId));
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            if (!rs.hasNext()) return null;
            BindingSet sol = rs.next();
            String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : localName(resource(sol, "prop"));
            String filler = resourceOrBlank(sol, "filler");
            String fillerLabel = sol.hasBinding("fillerLabel") ? literal(sol, "fillerLabel")
                    : (filler != null ? localName(filler) : "");
            String restrictionType = literal(sol, "restrictionType");
            String card = literal(sol, "card");
            if ("Self".equals(fillerLabel)) {
                return propLabel + " some Self";
            }
            if (!card.isBlank()) {
                return propLabel + " " + restrictionType + " " + card + " " + fillerLabel;
            }
            return propLabel + " " + restrictionType + " " + fillerLabel;
        } catch (Exception e) {
            log.debug("Could not describe restriction for {}: {}", blankNodeId, e.getMessage());
            return null;
        }
    }

    private List<String> describeBlankNodeMemberList(String projectId, String blankNodeId, String listPred) {
        String owlPred = "intersectionOf".equals(listPred) ? "owl:intersectionOf"
                : "unionOf".equals(listPred) ? "owl:unionOf" : "owl:oneOf";
        String query = PREFIXES + """
            SELECT ?member ?memberLabel WHERE {
              ?bnode %s ?list .
              ?list rdf:rest*/rdf:first ?member .
              %s
              OPTIONAL { ?member rdfs:label ?memberLabel }
            }
            """.formatted(owlPred, blankNodeFilterClause("?bnode", blankNodeId));
        List<String> labels = new ArrayList<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String member = resourceOrBlank(sol, "member");
                if (member == null) continue;
                String label = sol.hasBinding("memberLabel") ? literal(sol, "memberLabel") : localName(member);
                labels.add(label.isBlank() ? member : label);
            }
        } catch (Exception e) {
            log.debug("Could not describe {} for {}: {}", listPred, blankNodeId, e.getMessage());
        }
        return labels;
    }

    private String describeBlankNodeComplement(String projectId, String blankNodeId) {
        String query = PREFIXES + """
            SELECT ?complement ?complementLabel WHERE {
              ?bnode owl:complementOf ?complement .
              %s
              OPTIONAL { ?complement rdfs:label ?complementLabel }
            }
            LIMIT 1
            """.formatted(blankNodeFilterClause("?bnode", blankNodeId));
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            if (!rs.hasNext()) return null;
            BindingSet sol = rs.next();
            String complement = resourceOrBlank(sol, "complement");
            if (complement == null) return null;
            String label = sol.hasBinding("complementLabel") ? literal(sol, "complementLabel") : localName(complement);
            return label.isBlank() ? complement : label;
        } catch (Exception e) {
            log.debug("Could not describe complement for {}: {}", blankNodeId, e.getMessage());
            return null;
        }
    }

    private String resourceOrBlank(BindingSet sol, String var) {
        if (sol.hasBinding(var)) {
            Value node = sol.getValue(var);
            if (node != null && node.isIRI()) {
                return node.stringValue();
            }
            if (node != null && node.isBNode()) {
                return "_:" + node.stringValue();
            }
        }
        return null;
    }

    private String resource(BindingSet sol, String var) {
        if (sol.hasBinding(var)) {
            Value node = sol.getValue(var);
            if (node != null && node.isIRI()) {
                return node.stringValue();
            }
        }
        return null;
    }

    private String literal(BindingSet sol, String var) {
        if (sol.hasBinding(var)) {
            Value node = sol.getValue(var);
            if (node != null && node.isLiteral()) {
                return node.stringValue();
            }
        }
        return "";
    }

    private int literalToInt(BindingSet sol, String var) {
        if (sol.hasBinding(var)) {
            Value node = sol.getValue(var);
            if (node != null && node.isLiteral()) {
                try {
                    return Integer.parseInt(node.stringValue());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private List<String> splitPipe(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String part : value.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return new ArrayList<>(unique);
    }

    private String localName(String iri) {
        if (iri == null || iri.isBlank()) {
            return "";
        }
        int idx = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return idx >= 0 && idx < iri.length() - 1 ? iri.substring(idx + 1) : iri;
    }

    /**
     * Format an IRI with proper prefix for display (e.g., owl:topObjectProperty instead of topObjectProperty)
     */
    private String formatIriWithPrefix(String iri) {
        if (iri == null || iri.isBlank()) {
            return "";
        }
        // Handle OWL namespace
        if (iri.startsWith("http://www.w3.org/2002/07/owl#")) {
            return "owl:" + iri.substring("http://www.w3.org/2002/07/owl#".length());
        }
        // Handle RDF namespace
        if (iri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")) {
            return "rdf:" + iri.substring("http://www.w3.org/1999/02/22-rdf-syntax-ns#".length());
        }
        // Handle RDFS namespace
        if (iri.startsWith("http://www.w3.org/2000/01/rdf-schema#")) {
            return "rdfs:" + iri.substring("http://www.w3.org/2000/01/rdf-schema#".length());
        }
        // Handle XSD namespace
        if (iri.startsWith("http://www.w3.org/2001/XMLSchema#")) {
            return "xsd:" + iri.substring("http://www.w3.org/2001/XMLSchema#".length());
        }
        // Default to local name for custom ontology entities
        return localName(iri);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public List<String> ontologyImports(String projectId) {
        String query = PREFIXES + """
            SELECT DISTINCT ?import WHERE {
              ?ont a owl:Ontology .
              ?ont owl:imports ?import .
            }
            ORDER BY ?import
            """;
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<String> imports = new ArrayList<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            Value importVal = sol.getValue("import");
            if (importVal != null) {
                imports.add(importVal.stringValue());
            }
        }
        return imports;
    }

    public List<Map<String, String>> generalClassAxioms(String projectId, int limit) {
        String query = PREFIXES + """
            SELECT DISTINCT ?sub ?super ?label WHERE {
              ?sub rdfs:subClassOf ?super .
              FILTER(isBlank(?sub))
              OPTIONAL { ?super rdfs:label ?label }
            }
            """;
        TupleQueryResult rs = datasetService.execSelect(projectId, query);
        List<Map<String, String>> axioms = new ArrayList<>();
        int count = 0;
        while (rs.hasNext() && count < limit) {
            BindingSet sol = rs.next();
            Value subVal = sol.getValue("sub");
            String subExpr = subVal != null ? subVal.stringValue() : "Anonymous class expression";
            String superIri = resource(sol, "super");
            String superLabel = sol.hasBinding("label") ? literal(sol, "label") : localName(superIri);

            Map<String, String> axiom = new LinkedHashMap<>();
            axiom.put("id", subExpr);
            axiom.put("subExpression", subExpr);
            axiom.put("superClassIri", superIri);
            axiom.put("superClassLabel", superLabel);
            axiom.put("definition", "Anonymous class expression <= " + (superLabel.isBlank() ? superIri : superLabel));
            axioms.add(axiom);
            count++;
        }
        return axioms;
    }

    @Cacheable(value = "debugInfo", key = "#projectId")
    public Map<String, Object> debugInfo(String projectId) {
        long startTime = System.currentTimeMillis();
        // Count all triples
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        TupleQueryResult rs1 = datasetService.execSelect(projectId, countQuery);
        long totalTriples = 0;
        if (rs1.hasNext()) {
            BindingSet sol = rs1.next();
            if (sol.hasBinding("count")) {
                Value countValue = sol.getValue("count");
                if (countValue.isLiteral()) {
                    totalTriples = Long.parseLong(countValue.stringValue());
                }
            }
        }

        // Count OWL classes (explicit or implicit)
        String classQuery = PREFIXES + """
            SELECT (COUNT(DISTINCT ?c) AS ?count) WHERE {
              { ?c a owl:Class }
              UNION { ?c rdfs:subClassOf ?any }
              UNION { ?any rdfs:subClassOf ?c }
            }
            """;
        TupleQueryResult rs2 = datasetService.execSelect(projectId, classQuery);
        long classCount = 0;
        if (rs2.hasNext()) {
            BindingSet sol = rs2.next();
            if (sol.hasBinding("count")) {
                Value countValue = sol.getValue("count");
                if (countValue.isLiteral()) {
                    classCount = Long.parseLong(countValue.stringValue());
                }
            }
        }

        // Count annotation properties
        String annQuery = PREFIXES + "SELECT (COUNT(DISTINCT ?p) AS ?count) WHERE { ?p a owl:AnnotationProperty }";
        TupleQueryResult rs3 = datasetService.execSelect(projectId, annQuery);
        long annCount = 0;
        if (rs3.hasNext()) {
            BindingSet sol = rs3.next();
            if (sol.hasBinding("count")) {
                Value countValue = sol.getValue("count");
                if (countValue.isLiteral()) {
                    annCount = Long.parseLong(countValue.stringValue());
                }
            }
        }

        // Sample some triples
        String sampleQuery = "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10";
        TupleQueryResult rs4 = datasetService.execSelect(projectId, sampleQuery);
        List<String> sampleTriples = new ArrayList<>();
        while (rs4.hasNext()) {
            BindingSet sol = rs4.next();
            sampleTriples.add(sol.getValue("s") + " " + sol.getValue("p") + " " + sol.getValue("o"));
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] debugInfo totalTriples={} classes={} in {}ms project={}", totalTriples, classCount, duration, projectId);
        return Map.of(
            "totalTriples", totalTriples,
            "classCount", classCount,
            "annotationPropertyCount", annCount,
            "sampleTriples", sampleTriples
        );
    }

    public List<Map<String, String>> classUsage(String projectId, String classIri) {
        safeIri(classIri);
        List<Map<String, String>> usages = new ArrayList<>();

        // 1. Find subclasses
        String subclassQuery = PREFIXES + """
            SELECT DISTINCT ?subclass ?label WHERE {
              ?subclass rdfs:subClassOf <%s> .
              OPTIONAL { ?subclass rdfs:label ?label }
            }
            """.formatted(classIri);
        TupleQueryResult subclasses = datasetService.execSelect(projectId, subclassQuery);
        while (subclasses.hasNext()) {
            BindingSet sol = subclasses.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "subclass");
            String subclassIri = resource(sol, "subclass");
            if (subclassIri != null) {
                usage.put("subject", subclassIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(subclassIri));
                usage.put("context", "SubClassOf " + classIri);
                usages.add(usage);
            }
        }
        
        // 2. Find individuals of this class
        String instanceQuery = PREFIXES + """
            SELECT DISTINCT ?individual ?label WHERE {
              ?individual a <%s> .
              OPTIONAL { ?individual rdfs:label ?label }
            }
            """.formatted(classIri);
        TupleQueryResult instances = datasetService.execSelect(projectId, instanceQuery);
        while (instances.hasNext()) {
            BindingSet sol = instances.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "instance");
            String individualIri = resource(sol, "individual");
            if (individualIri != null) {
                usage.put("subject", individualIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(individualIri));
                usage.put("context", "Individual of " + classIri);
                usages.add(usage);
            }
        }
        
        // 3. Find disjoint classes
        String disjointQuery = PREFIXES + """
            SELECT DISTINCT ?disjoint ?label WHERE {
              {
                <%s> owl:disjointWith ?disjoint .
              } UNION {
                ?disjoint owl:disjointWith <%s> .
              }
              OPTIONAL { ?disjoint rdfs:label ?label }
            }
            """.formatted(classIri, classIri);
        TupleQueryResult disjoints = datasetService.execSelect(projectId, disjointQuery);
        while (disjoints.hasNext()) {
            BindingSet sol = disjoints.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "disjoint");
            String disjointIri = resource(sol, "disjoint");
            if (disjointIri != null) {
                usage.put("subject", disjointIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(disjointIri));
                usage.put("context", "DisjointWith");
                usages.add(usage);
            }
        }
        
        // 4. Find named superclasses
        String superclassQuery = PREFIXES + """
            SELECT DISTINCT ?superclass ?label WHERE {
              <%s> rdfs:subClassOf ?superclass .
              FILTER(isIRI(?superclass) && ?superclass != owl:Thing)
              OPTIONAL { ?superclass rdfs:label ?label }
            }
            """.formatted(classIri);
        TupleQueryResult superclasses = datasetService.execSelect(projectId, superclassQuery);
        while (superclasses.hasNext()) {
            BindingSet sol = superclasses.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "superclass");
            String superclassIri = resource(sol, "superclass");
            if (superclassIri != null) {
                usage.put("subject", superclassIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(superclassIri));
                usage.put("context", "SuperClassOf");
                usages.add(usage);
            }
        }
        
        // 5. Find properties with this class as domain
        String domainQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?label WHERE {
              ?prop rdfs:domain <%s> .
              OPTIONAL { ?prop rdfs:label ?label }
            }
            """.formatted(classIri);
        TupleQueryResult domains = datasetService.execSelect(projectId, domainQuery);
        while (domains.hasNext()) {
            BindingSet sol = domains.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "domain");
            String propIri = resource(sol, "prop");
            if (propIri != null) {
                usage.put("subject", propIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(propIri));
                usage.put("context", "Domain of property");
                usages.add(usage);
            }
        }
        
        // 6. Find properties with this class as range
        String rangeQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?label WHERE {
              ?prop rdfs:range <%s> .
              OPTIONAL { ?prop rdfs:label ?label }
            }
            """.formatted(classIri);
        TupleQueryResult ranges = datasetService.execSelect(projectId, rangeQuery);
        while (ranges.hasNext()) {
            BindingSet sol = ranges.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "range");
            String propIri = resource(sol, "prop");
            if (propIri != null) {
                usage.put("subject", propIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(propIri));
                usage.put("context", "Range of property");
                usages.add(usage);
            }
        }
        
        // 7. Find restrictions using this class (owl:onClass)
        String restrictionQuery = PREFIXES + """
            SELECT DISTINCT ?restriction ?onProp ?propLabel WHERE {
              ?restriction a owl:Restriction ;
                           owl:onClass <%s> ;
                           owl:onProperty ?onProp .
              OPTIONAL { ?onProp rdfs:label ?propLabel }
            }
            """.formatted(classIri);
        TupleQueryResult restrictions = datasetService.execSelect(projectId, restrictionQuery);
        while (restrictions.hasNext()) {
            BindingSet sol = restrictions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "restriction");
            String restrictionIri = resource(sol, "restriction");
            String onPropIri = resource(sol, "onProp");
            if (restrictionIri != null && onPropIri != null) {
                usage.put("subject", restrictionIri);
                String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : localName(onPropIri);
                usage.put("subjectLabel", "Restriction on " + propLabel);
                usage.put("context", "Used in restriction");
                usages.add(usage);
            }
        }
        
        // 8. Find equivalent classes
        String equivQuery = PREFIXES + """
            SELECT DISTINCT ?equiv ?label WHERE {
              {
                <%s> owl:equivalentClass ?equiv .
              } UNION {
                ?equiv owl:equivalentClass <%s> .
              }
              FILTER(isIRI(?equiv))
              OPTIONAL { ?equiv rdfs:label ?label }
            }
            """.formatted(classIri, classIri);
        TupleQueryResult equivs = datasetService.execSelect(projectId, equivQuery);
        while (equivs.hasNext()) {
            BindingSet sol = equivs.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "equivalent");
            String equivIri = resource(sol, "equiv");
            if (equivIri != null) {
                usage.put("subject", equivIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(equivIri));
                usage.put("context", "EquivalentClass");
                usages.add(usage);
            }
        }
        
        // 9. Find union/intersection members
        String unionIntersectionQuery = PREFIXES + """
            SELECT DISTINCT ?owner ?label ?type WHERE {
              {
                ?owner owl:unionOf ?list .
                ?list rdf:rest*/rdf:first <%s> .
                BIND("union" AS ?type)
              } UNION {
                ?owner owl:intersectionOf ?list .
                ?list rdf:rest*/rdf:first <%s> .
                BIND("intersection" AS ?type)
              }
              OPTIONAL { ?owner rdfs:label ?label }
            }
            """.formatted(classIri, classIri);
        TupleQueryResult unionIntersections = datasetService.execSelect(projectId, unionIntersectionQuery);
        while (unionIntersections.hasNext()) {
            BindingSet sol = unionIntersections.next();
            Map<String, String> usage = new LinkedHashMap<>();
            String typeStr = literal(sol, "type");
            usage.put("type", typeStr);
            String ownerIri = resource(sol, "owner");
            if (ownerIri != null) {
                usage.put("subject", ownerIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(ownerIri));
                usage.put("context", "Member of " + typeStr);
                usages.add(usage);
            }
        }
        
        // 10. Find all annotation property assertions pointing to this class
        String annotationUsageQuery = PREFIXES + """
            SELECT DISTINCT ?subject ?prop ?propLabel ?subjectLabel WHERE {
              ?subject ?prop <%s> .
              {
                ?prop a owl:AnnotationProperty .
              } UNION {
                VALUES ?prop { rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy }
              }
              OPTIONAL { ?prop rdfs:label ?propLabel }
              OPTIONAL { ?subject rdfs:label ?subjectLabel }
            }
            """.formatted(classIri);
        TupleQueryResult annotationUsages = datasetService.execSelect(projectId, annotationUsageQuery);
        while (annotationUsages.hasNext()) {
            BindingSet sol = annotationUsages.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "annotation");
            String subjectIri = resource(sol, "subject");
            String propIri = resource(sol, "prop");
            if (subjectIri != null && propIri != null) {
                usage.put("subject", subjectIri);
                String subjLabel = sol.hasBinding("subjectLabel") ? literal(sol, "subjectLabel") : localName(subjectIri);
                String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : localName(propIri);
                usage.put("subjectLabel", subjLabel);
                usage.put("context", "Annotation: " + propLabel);
                usages.add(usage);
            }
        }
        
        // 11. Find all annotation properties ON this class (annotations declared on the class itself)
        String classAnnotationsQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?value ?propLabel WHERE {
              <%s> ?prop ?value .
              FILTER(isLiteral(?value) || isIRI(?value))
              {
                ?prop a owl:AnnotationProperty .
              } UNION {
                VALUES ?prop { rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy }
              }
              OPTIONAL { ?prop rdfs:label ?propLabel }
            }
            """.formatted(classIri);
        TupleQueryResult classAnnotations = datasetService.execSelect(projectId, classAnnotationsQuery);
        while (classAnnotations.hasNext()) {
            BindingSet sol = classAnnotations.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "annotation_on_class");
            String propIri = resource(sol, "prop");
            if (propIri != null) {
                // Skip rdf:type as it's shown elsewhere
                if (propIri.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")) {
                    continue;
                }
                
                usage.put("subject", classIri);
                String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : localName(propIri);
                String value = sol.hasBinding("value") ? sol.getValue("value").stringValue() : "";
                
                // Truncate long values for display
                if (value.length() > 100) {
                    value = value.substring(0, 97) + "...";
                }
                
                usage.put("subjectLabel", propLabel);
                usage.put("context", value);
                usage.put("annotationProperty", propIri);
                usages.add(usage);
            }
        }
        
        return usages;
    }

    public List<Map<String, String>> propertyUsage(String projectId, String propertyIri) {
        safeIri(propertyIri);
        List<Map<String, String>> usages = new ArrayList<>();

        // 1. Find domains
        String domainQuery = PREFIXES + """
            SELECT DISTINCT ?domain ?label WHERE {
              <%s> rdfs:domain ?domain .
              FILTER(isIRI(?domain))
              OPTIONAL { ?domain rdfs:label ?label }
            }
            """.formatted(propertyIri);
        TupleQueryResult domains = datasetService.execSelect(projectId, domainQuery);
        while (domains.hasNext()) {
            BindingSet sol = domains.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "domain");
            String domainIri = resource(sol, "domain");
            if (domainIri != null) {
                usage.put("subject", domainIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(domainIri));
                usage.put("context", "Domain of property");
                usages.add(usage);
            }
        }
        
        // 2. Find ranges
        String rangeQuery = PREFIXES + """
            SELECT DISTINCT ?range ?label WHERE {
              <%s> rdfs:range ?range .
              FILTER(isIRI(?range))
              OPTIONAL { ?range rdfs:label ?label }
            }
            """.formatted(propertyIri);
        TupleQueryResult ranges = datasetService.execSelect(projectId, rangeQuery);
        while (ranges.hasNext()) {
            BindingSet sol = ranges.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "range");
            String rangeIri = resource(sol, "range");
            if (rangeIri != null) {
                usage.put("subject", rangeIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(rangeIri));
                usage.put("context", "Range of property");
                usages.add(usage);
            }
        }
        
        // 3. Find subproperties
        String subPropQuery = PREFIXES + """
            SELECT DISTINCT ?sub ?label WHERE {
              ?sub rdfs:subPropertyOf <%s> .
              FILTER(isIRI(?sub) && ?sub != <%s>)
              OPTIONAL { ?sub rdfs:label ?label }
            }
            """.formatted(propertyIri, propertyIri);
        TupleQueryResult subProps = datasetService.execSelect(projectId, subPropQuery);
        while (subProps.hasNext()) {
            BindingSet sol = subProps.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "subproperty");
            String subIri = resource(sol, "sub");
            if (subIri != null) {
                usage.put("subject", subIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(subIri));
                usage.put("context", "SubPropertyOf");
                usages.add(usage);
            }
        }

        // 4. Find superproperties
        String superPropQuery = PREFIXES + """
            SELECT DISTINCT ?super ?label WHERE {
              <%s> rdfs:subPropertyOf ?super .
              FILTER(isIRI(?super) && ?super != <%s>)
              OPTIONAL { ?super rdfs:label ?label }
            }
            """.formatted(propertyIri, propertyIri);
        TupleQueryResult superProps = datasetService.execSelect(projectId, superPropQuery);
        while (superProps.hasNext()) {
            BindingSet sol = superProps.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "superproperty");
            String superIri = resource(sol, "super");
            if (superIri != null) {
                usage.put("subject", superIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(superIri));
                usage.put("context", "SuperPropertyOf");
                usages.add(usage);
            }
        }
        
        // 5. Find property assertions in individuals
        String assertionQuery = PREFIXES + """
            SELECT DISTINCT ?subject ?label WHERE {
              ?subject <%s> ?any .
              OPTIONAL { ?subject rdfs:label ?label }
            }
            LIMIT 1000
            """.formatted(propertyIri);
        TupleQueryResult assertions = datasetService.execSelect(projectId, assertionQuery);
        while (assertions.hasNext()) {
            BindingSet sol = assertions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "assertion");
            String subjectIri = resource(sol, "subject");
            if (subjectIri != null) {
                usage.put("subject", subjectIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(subjectIri));
                usage.put("context", "Property assertion");
                usages.add(usage);
            }
        }
        
        // 6. Find restrictions using this property
        String restrictionQuery = PREFIXES + """
            SELECT DISTINCT ?class ?label WHERE {
              ?class rdfs:subClassOf ?restriction .
              ?restriction a owl:Restriction ;
                           owl:onProperty <%s> .
              FILTER(isIRI(?class))
              OPTIONAL { ?class rdfs:label ?label }
            }
            """.formatted(propertyIri);
        TupleQueryResult restrictions = datasetService.execSelect(projectId, restrictionQuery);
        while (restrictions.hasNext()) {
            BindingSet sol = restrictions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "restriction");
            String classIri = resource(sol, "class");
            if (classIri != null) {
                usage.put("subject", classIri);
                usage.put("subjectLabel", sol.hasBinding("label") ? literal(sol, "label") : localName(classIri));
                usage.put("context", "Used in restriction");
                usages.add(usage);
            }
        }
        
        return usages;
    }

    /**
     * Fast-path: returns ONLY the annotations + label for a class. Runs a
     * single SPARQL query (typically <100ms). Used by the UI to render the
     * Annotations panel immediately on class click, while the full
     * {@link #classDetails} call completes in the background to hydrate the
     * rest of the panels (SubClassOf, EquivalentTo, DisjointWith, restrictions,
     * inferred axioms, GCI, etc.).
     *
     * Response shape is a strict subset of {@link #classDetails} so the
     * frontend can merge results without schema translation.
     */
    @Cacheable(value = "classAnnotations", key = "#projectId + '_' + #classIri", sync = true)
    public Map<String, Object> classAnnotations(String projectId, String classIri) {
        safeIri(classIri);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", classIri);

        String annQuery = PREFIXES + """
            SELECT ?prop ?value WHERE {
              <%s> ?prop ?value .
              FILTER(isLiteral(?value) || isIRI(?value))
              {
                ?prop a owl:AnnotationProperty .
              } UNION {
                VALUES ?prop {
                  rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy
                  owl:deprecated owl:versionInfo owl:backwardCompatibleWith
                  owl:incompatibleWith owl:priorVersion
                }
              }
            }
            """.formatted(classIri);

        Map<String, List<String>> annotations = AnnotationValueCollector.newMap();
        String label = null;
        try {
            TupleQueryResult annRs = datasetService.execSelect(projectId, annQuery);
            while (annRs.hasNext()) {
                BindingSet sol = annRs.next();
                String propIri = resource(sol, "prop");
                if (propIri != null && sol.hasBinding("value")) {
                    Value valueNode = sol.getValue("value");
                    String value = valueNode.isLiteral() ? valueNode.stringValue() : valueNode.toString();
                    AnnotationValueCollector.add(annotations, propIri, value);
                    if (label == null && propIri.endsWith("#label")) {
                        label = value;
                    }
                }
            }
        } catch (Exception e) {
            // Fail soft — UI will fall back to the full classDetails call.
            result.put("label", localName(classIri));
            result.put("annotations", annotations);
            return result;
        }
        result.put("label", label != null ? label : localName(classIri));
        result.put("annotations", annotations);
        return result;
    }

    @Cacheable(value = "classDetails", key = "#projectId + '_' + #classIri", sync = true)
    public Map<String, Object> classDetails(String projectId, String classIri) {
        safeIri(classIri);
        long startTime = System.currentTimeMillis();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", classIri);
        final Executor queryPool = queryExecutorFor(projectId);
        if (datasetService.isKnownLargeProject(projectId)) {
            log.info("[PERF] classDetails using reduced SPARQL parallelism for large project {}", projectId);
        }
        
        // ===== PARALLEL EXECUTION: Run all independent SPARQL queries concurrently =====
        // Each query is independent (read-only), so they can all run in parallel.
        // This reduces total time from sum(all queries) to max(slowest query).
        
        // --- Annotations query ---
        CompletableFuture<TupleQueryResult> annFuture = CompletableFuture.supplyAsync(() -> {
            String annQuery = PREFIXES + """
                SELECT ?prop ?value WHERE {
                  <%s> ?prop ?value .
                  FILTER(isLiteral(?value) || isIRI(?value))
                  {
                    ?prop a owl:AnnotationProperty .
                  } UNION {
                    VALUES ?prop {
                      rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy
                      owl:deprecated owl:versionInfo owl:backwardCompatibleWith
                      owl:incompatibleWith owl:priorVersion
                    }
                  }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, annQuery);
        }, queryPool);
        
        // --- SubClassOf named superclasses ---
        CompletableFuture<TupleQueryResult> subClassFuture = CompletableFuture.supplyAsync(() -> {
            String subClassQuery = PREFIXES + """
                SELECT DISTINCT ?super ?label WHERE {
                  <%s> rdfs:subClassOf ?super .
                  FILTER(isIRI(?super))
                  FILTER(?super != owl:Thing)
                  FILTER(?super != <%s>)
                  FILTER(?super != owl:Nothing)
                  FILTER(!STRSTARTS(STR(?super), "http://www.w3.org/2002/07/owl#"))
                  FILTER(!STRSTARTS(STR(?super), "http://www.w3.org/2000/01/rdf-schema#"))
                  OPTIONAL { ?super rdfs:label ?label }
                }
                ORDER BY ?label
                """.formatted(classIri, classIri, classIri);
            return datasetService.execSelect(projectId, subClassQuery);
        }, queryPool);
        
        // --- SubClassOf restrictions ---
        CompletableFuture<TupleQueryResult> subClassRestrictionFuture = CompletableFuture.supplyAsync(() -> {
            String subClassRestrictionQuery = PREFIXES + """
                SELECT DISTINCT ?restriction ?prop ?propLabel ?restrictionType ?filler ?fillerLabel ?card ?propType WHERE {
                  <%s> rdfs:subClassOf ?restriction .
                  ?restriction a owl:Restriction ;
                              owl:onProperty ?prop .
                  OPTIONAL { ?prop rdfs:label ?propLabel }
                  OPTIONAL { ?prop a ?propType . FILTER(?propType IN (owl:ObjectProperty, owl:DatatypeProperty)) }
                  OPTIONAL {
                    { ?restriction owl:someValuesFrom ?filler . BIND("some" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:allValuesFrom ?filler . BIND("only" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:hasValue ?filler . BIND("value" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:hasSelf true . BIND("Self" AS ?filler) . BIND("some" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:minQualifiedCardinality ?card . BIND("min" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:maxQualifiedCardinality ?card . BIND("max" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:qualifiedCardinality ?card . BIND("exactly" AS ?restrictionType) }
                    UNION
                    {
                      ?restriction owl:minCardinality ?card .
                      FILTER NOT EXISTS { ?restriction owl:minQualifiedCardinality ?any }
                      BIND("min" AS ?restrictionType)
                      BIND(owl:Thing AS ?filler)
                    }
                    UNION
                    {
                      ?restriction owl:maxCardinality ?card .
                      FILTER NOT EXISTS { ?restriction owl:maxQualifiedCardinality ?any }
                      BIND("max" AS ?restrictionType)
                      BIND(owl:Thing AS ?filler)
                    }
                    UNION
                    {
                      ?restriction owl:cardinality ?card .
                      FILTER NOT EXISTS { ?restriction owl:qualifiedCardinality ?any }
                      BIND("exactly" AS ?restrictionType)
                      BIND(owl:Thing AS ?filler)
                    }
                  }
                  OPTIONAL { ?restriction owl:onClass ?filler }
                  OPTIONAL { ?restriction owl:onDataRange ?filler }
                  OPTIONAL { ?filler rdfs:label ?fillerLabel }
                  FILTER(BOUND(?restrictionType))
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, subClassRestrictionQuery);
        }, queryPool);
        
        // --- SubClassOf anonymous expressions (intersection / union / complement merged) ---
        CompletableFuture<TupleQueryResult> subClassAnonymousFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?bnode ?member ?memberLabel ?exprType WHERE {
                  <%s> rdfs:subClassOf ?bnode .
                  FILTER(isBlank(?bnode))
                  {
                    { ?bnode owl:intersectionOf ?list . BIND("intersection" AS ?exprType) }
                    UNION
                    { ?bnode owl:unionOf ?list . BIND("union" AS ?exprType) }
                    ?list rdf:rest*/rdf:first ?member .
                    FILTER(isIRI(?member))
                  }
                  UNION
                  {
                    ?bnode owl:complementOf ?member . BIND("complement" AS ?exprType)
                    FILTER(isIRI(?member))
                  }
                  OPTIONAL { ?member rdfs:label ?memberLabel }
                }
                LIMIT 500
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);
        
        // --- EquivalentClass simple ---
        // owl:equivalentClass is symmetric, but we store only one direction.
        // UNION covers both: ?classIri owl:equivalentClass ?equiv AND ?equiv owl:equivalentClass ?classIri
        CompletableFuture<TupleQueryResult> equivFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?equiv ?label WHERE {
                  {
                    <%s> owl:equivalentClass ?equiv .
                    FILTER(isIRI(?equiv) && ?equiv != <%s>)
                  } UNION {
                    ?equiv owl:equivalentClass <%s> .
                    FILTER(isIRI(?equiv) && ?equiv != <%s>)
                  }
                  OPTIONAL { ?equiv rdfs:label ?label }
                }
                """.formatted(classIri, classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);
        
        // --- EquivalentClass restrictions ---
        CompletableFuture<TupleQueryResult> equivRestrictionFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?restriction ?prop ?propLabel ?restrictionType ?filler ?fillerLabel ?card WHERE {
                  <%s> owl:equivalentClass ?restriction .
                  ?restriction a owl:Restriction ;
                              owl:onProperty ?prop .
                  OPTIONAL { ?prop rdfs:label ?propLabel }
                  OPTIONAL {
                    { ?restriction owl:someValuesFrom ?filler . BIND("some" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:allValuesFrom ?filler . BIND("only" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:hasValue ?filler . BIND("value" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:hasSelf true . BIND("Self" AS ?filler) . BIND("some" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:minQualifiedCardinality ?card . BIND("min" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:maxQualifiedCardinality ?card . BIND("max" AS ?restrictionType) }
                    UNION
                    { ?restriction owl:qualifiedCardinality ?card . BIND("exactly" AS ?restrictionType) }
                    UNION
                    {
                      ?restriction owl:minCardinality ?card .
                      FILTER NOT EXISTS { ?restriction owl:minQualifiedCardinality ?any }
                      BIND("min" AS ?restrictionType)
                      BIND(owl:Thing AS ?filler)
                    }
                    UNION
                    {
                      ?restriction owl:maxCardinality ?card .
                      FILTER NOT EXISTS { ?restriction owl:maxQualifiedCardinality ?any }
                      BIND("max" AS ?restrictionType)
                      BIND(owl:Thing AS ?filler)
                    }
                    UNION
                    {
                      ?restriction owl:cardinality ?card .
                      FILTER NOT EXISTS { ?restriction owl:qualifiedCardinality ?any }
                      BIND("exactly" AS ?restrictionType)
                      BIND(owl:Thing AS ?filler)
                    }
                  }
                  OPTIONAL { ?restriction owl:onClass ?filler }
                  OPTIONAL { ?restriction owl:onDataRange ?filler }
                  OPTIONAL { ?filler rdfs:label ?fillerLabel }
                  FILTER(BOUND(?restrictionType))
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);
        
        // --- EquivalentClass anonymous expressions (intersection/union/complement/oneOf merged) ---
        CompletableFuture<TupleQueryResult> equivAnonymousFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?bnode ?member ?memberLabel ?exprType WHERE {
                  <%s> owl:equivalentClass ?bnode .
                  FILTER(isBlank(?bnode))
                  {
                    { ?bnode owl:intersectionOf ?list . BIND("intersection" AS ?exprType) }
                    UNION
                    { ?bnode owl:unionOf ?list . BIND("union" AS ?exprType) }
                    ?list rdf:rest*/rdf:first ?member .
                    FILTER(isIRI(?member))
                  }
                  UNION
                  {
                    ?bnode owl:complementOf ?member . BIND("complement" AS ?exprType)
                    FILTER(isIRI(?member))
                  }
                  UNION
                  {
                    ?bnode owl:oneOf ?list . BIND("oneOf" AS ?exprType)
                    ?list rdf:rest*/rdf:first ?member .
                  }
                  OPTIONAL { ?member rdfs:label ?memberLabel }
                }
                LIMIT 500
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);
        
        // --- DisjointWith ---
        CompletableFuture<TupleQueryResult> disjointFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?disjoint ?label WHERE {
                  {
                    <%s> owl:disjointWith ?disjoint .
                  } UNION {
                    ?disjoint owl:disjointWith <%s> .
                  } UNION {
                    ?allDisjoint a owl:AllDisjointClasses ;
                                 owl:members ?list .
                    ?list rdf:rest*/rdf:first <%s> .
                    ?list rdf:rest*/rdf:first ?disjoint .
                    FILTER(?disjoint != <%s>)
                  }
                  FILTER(isIRI(?disjoint) && ?disjoint != <%s>)
                  OPTIONAL { ?disjoint rdfs:label ?label }
                }
                ORDER BY ?label
                """.formatted(classIri, classIri, classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);
        
        // --- DisjointUnionOf + HasKey merged ---
        CompletableFuture<TupleQueryResult> listAxiomsFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?axType ?list ?prop WHERE {
                  {
                    <%s> owl:disjointUnionOf ?list . BIND("disjointUnion" AS ?axType)
                    ?list rdf:rest*/rdf:first ?prop .
                  }
                  UNION
                  {
                    <%s> owl:hasKey ?list . BIND("hasKey" AS ?axType)
                    ?list rdf:rest*/rdf:first ?prop .
                  }
                }
                LIMIT 500
                """.formatted(classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);
        
        // --- Inferred equivalent classes ---
        CompletableFuture<TupleQueryResult> inferredEquivFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?equiv ?label WHERE {
                  GRAPH <http://www.ontotext.com/inferred> {
                    <%s> owl:equivalentClass ?equiv .
                  }
                  FILTER NOT EXISTS {
                    GRAPH <http://www.ontotext.com/explicit> {
                      <%s> owl:equivalentClass ?equiv .
                    }
                  }
                  FILTER(isIRI(?equiv) && ?equiv != <%s>)
                  OPTIONAL { ?equiv rdfs:label ?label }
                }
                """.formatted(classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);
        
        // --- Inferred superclasses ---
        CompletableFuture<TupleQueryResult> inferredSuperFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?super ?label WHERE {
                  GRAPH <http://www.ontotext.com/inferred> {
                    <%s> rdfs:subClassOf ?super .
                  }
                  FILTER NOT EXISTS {
                    GRAPH <http://www.ontotext.com/explicit> {
                      <%s> rdfs:subClassOf ?super .
                    }
                  }
                  FILTER(isIRI(?super) && ?super != owl:Thing && ?super != <%s>)
                  OPTIONAL { ?super rdfs:label ?label }
                }
                """.formatted(classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);
        
        // --- Inferred disjoint classes ---
        CompletableFuture<TupleQueryResult> inferredDisjointFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?disjoint ?label WHERE {
                  GRAPH <http://www.ontotext.com/inferred> {
                    {
                      <%s> owl:disjointWith ?disjoint .
                    } UNION {
                      ?disjoint owl:disjointWith <%s> .
                    }
                  }
                  FILTER NOT EXISTS {
                    GRAPH <http://www.ontotext.com/explicit> {
                      {
                        <%s> owl:disjointWith ?disjoint .
                      } UNION {
                        ?disjoint owl:disjointWith <%s> .
                      }
                    }
                  }
                  FILTER(isIRI(?disjoint) && ?disjoint != <%s>)
                  OPTIONAL { ?disjoint rdfs:label ?label }
                }
                """.formatted(classIri, classIri, classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);
        
        // --- GCI axioms ---
        // OPTIMIZED: The previous query did a `?subExpr ?p ?o` cross-product with
        // `rdfs:subClassOf` + FILTER(isBlank), which forced a full scan over every
        // triple whose subject is a blank node. On ontologies with many anonymous
        // class expressions this was the dominant cost of classDetails (observed
        // 60s+). Rewritten to index-driven form: directly look up blank-node
        // subClassOf axioms and only then test whether this class appears
        // somewhere inside the sub-expression.
        // Member traversal is done inline in the same query to avoid blank node ID
        // instability across separate SPARQL queries (blank node IDs from query 1
        // are not guaranteed to match in a second query against RDF4J/Fuseki).
        CompletableFuture<TupleQueryResult> gciFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?subExpr ?superClass ?superClassLabel ?exprType ?member ?memberLabel WHERE {
                  ?subExpr rdfs:subClassOf ?superClass .
                  FILTER(isBlank(?subExpr))
                  {
                    FILTER(?superClass = <%s>)
                  } UNION {
                    ?subExpr (rdf:first|rdf:rest|owl:intersectionOf|owl:unionOf|owl:complementOf|owl:someValuesFrom|owl:allValuesFrom|owl:onClass)+ <%s> .
                  }
                  OPTIONAL { ?superClass rdfs:label ?superClassLabel }
                  OPTIONAL {
                    {
                      { ?subExpr owl:intersectionOf ?list . BIND("intersection" AS ?exprType) }
                      UNION
                      { ?subExpr owl:unionOf ?list . BIND("union" AS ?exprType) }
                      ?list rdf:rest*/rdf:first ?member .
                      FILTER(isIRI(?member))
                    }
                    UNION
                    {
                      ?subExpr owl:complementOf ?member . BIND("complement" AS ?exprType)
                      FILTER(isIRI(?member))
                    }
                    OPTIONAL { ?member rdfs:label ?memberLabel }
                  }
                }
                LIMIT 500
                """.formatted(classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        // --- Anonymous ancestor superclasses ---
        // OPTIMIZED: Added explicit LIMIT to prevent runaway transitive path
        // expansion in deeply nested hierarchies. 500 is well above any realistic
        // ancestor count for a single class.
        CompletableFuture<TupleQueryResult> ancestorFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?ancestor ?super ?label WHERE {
                  <%s> rdfs:subClassOf+ ?ancestor .
                  ?ancestor rdfs:subClassOf ?super .
                  FILTER(isBlank(?super) || (?super != owl:Thing && ?super != <%s>))
                  OPTIONAL { ?super rdfs:label ?label }
                }
                LIMIT 500
                """.formatted(classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);
        
        // ===== Wait for all queries to complete =====
        // 20 queries → 14 after merging anonymous subClassOf (3→1), anonymous equivClass (4→1), disjointUnion+hasKey (2→1)
        CompletableFuture.allOf(
            annFuture, subClassFuture, subClassRestrictionFuture, subClassAnonymousFuture,
            equivFuture, equivRestrictionFuture, equivAnonymousFuture,
            disjointFuture, listAxiomsFuture,
            inferredEquivFuture, inferredSuperFuture, inferredDisjointFuture,
            gciFuture, ancestorFuture
        ).join();
        
        long queryTime = System.currentTimeMillis() - startTime;
        log.info("[PERF] classDetails all parallel queries completed in {}ms for {}", queryTime, localName(classIri));
        
        // ===== Process results (all in-memory, very fast) =====
        
        // --- Process annotations ---
        TupleQueryResult annRs = annFuture.join();
        Map<String, List<String>> annotations = AnnotationValueCollector.newMap();
        String label = null;
        while (annRs.hasNext()) {
            BindingSet sol = annRs.next();
            String propIri = resource(sol, "prop");
            if (propIri != null && sol.hasBinding("value")) {
                Value valueNode = sol.getValue("value");
                String value = valueNode.isLiteral() ? valueNode.stringValue() : valueNode.toString();
                AnnotationValueCollector.add(annotations, propIri, value);
                if (label == null && propIri.endsWith("#label")) {
                    label = value;
                }
            }
        }
        details.put("label", label != null ? label : localName(classIri));
        details.put("annotations", annotations);
        
        // --- Process SubClassOf axioms ---
        List<Map<String, String>> subClassAxioms = new ArrayList<>();
        TupleQueryResult subClassRs = subClassFuture.join();
        while (subClassRs.hasNext()) {
            BindingSet sol = subClassRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String superIri = resource(sol, "super");
            if (superIri != null) {
                axiom.put("id", superIri);
                axiom.put("type", "SubClassOf");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(superIri));
                subClassAxioms.add(axiom);
            }
        }
        
        // --- Process SubClassOf restrictions ---
        TupleQueryResult subClassRestrictionRs = subClassRestrictionFuture.join();
        Set<String> seenRestrictions = new LinkedHashSet<>();
        while (subClassRestrictionRs.hasNext()) {
            BindingSet sol = subClassRestrictionRs.next();
            String restrictionNode = sol.getValue("restriction").stringValue();
            if (seenRestrictions.contains(restrictionNode)) {
                continue;
            }
            seenRestrictions.add(restrictionNode);
            Map<String, String> axiom = new LinkedHashMap<>();
            String propIri = resource(sol, "prop");
            String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : formatIriWithPrefix(propIri);
            String restrictionType = sol.hasBinding("restrictionType") ? literal(sol, "restrictionType") : "some";
            String fillerIri = sol.hasBinding("filler") ? sol.getValue("filler").stringValue() : "";
            String fillerLabel = sol.hasBinding("fillerLabel") ? literal(sol, "fillerLabel") : formatIriWithPrefix(fillerIri);
            String cardinality = sol.hasBinding("card") ? literal(sol, "card") : "";
            String definition;
            if (!cardinality.isEmpty()) {
                definition = propLabel + " " + restrictionType + " " + cardinality + " " + fillerLabel;
            } else {
                definition = propLabel + " " + restrictionType + " " + fillerLabel;
            }
            axiom.put("id", restrictionNode);
            axiom.put("type", "SubClassOf");
            axiom.put("definition", definition);
            axiom.put("isRestriction", "true");
            axiom.put("propertyIri", propIri);
            axiom.put("restrictionType", restrictionType);
            axiom.put("fillerIri", fillerIri);
            if (!cardinality.isEmpty()) {
                axiom.put("cardinality", cardinality);
            }
            subClassAxioms.add(axiom);
        }
        
        // --- Process SubClassOf anonymous expressions (intersection / union / complement) ---
        TupleQueryResult subClassAnonRs = subClassAnonymousFuture.join();
        Map<String, List<String>> scAnonMemberIris = new LinkedHashMap<>();
        Map<String, List<String>> scAnonMemberLabels = new LinkedHashMap<>();
        Map<String, String> scAnonExprType = new LinkedHashMap<>();
        while (subClassAnonRs.hasNext()) {
            BindingSet sol = subClassAnonRs.next();
            String bnode = sol.getValue("bnode").stringValue();
            String memberIri = resource(sol, "member");
            String memberLabel = sol.hasBinding("memberLabel") ? literal(sol, "memberLabel") : localName(memberIri);
            String exprType = sol.hasBinding("exprType") ? literal(sol, "exprType") : "";
            if (memberIri != null) {
                scAnonMemberIris.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberIri);
                scAnonMemberLabels.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberLabel);
                scAnonExprType.putIfAbsent(bnode, exprType);
            }
        }
        for (Map.Entry<String, List<String>> entry : scAnonMemberIris.entrySet()) {
            String bnode = entry.getKey();
            List<String> labels = scAnonMemberLabels.get(bnode);
            if (labels == null || labels.isEmpty()) continue;
            String exprType = scAnonExprType.getOrDefault(bnode, "");
            String definition = switch (exprType) {
                case "intersection" -> String.join(" and ", labels);
                case "union"        -> String.join(" or ", labels);
                case "complement"   -> "not " + labels.get(0);
                default             -> String.join(", ", labels);
            };
            Map<String, String> axiom = new LinkedHashMap<>();
            axiom.put("id", bnode);
            axiom.put("type", "SubClassOf");
            axiom.put("definition", definition);
            axiom.put("isComplex", "true");
            axiom.put("expressionType", exprType);
            subClassAxioms.add(axiom);
        }
        
        details.put("subClassOfAxioms", subClassAxioms);
        
        // --- Process EquivalentClass simple ---
        List<Map<String, String>> equivAxioms = new ArrayList<>();
        TupleQueryResult equivRs = equivFuture.join();
        while (equivRs.hasNext()) {
            BindingSet sol = equivRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String equivIri = resource(sol, "equiv");
            if (equivIri != null) {
                axiom.put("id", equivIri);
                axiom.put("type", "EquivalentTo");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(equivIri));
                equivAxioms.add(axiom);
            }
        }
        
        // --- Process EquivalentClass restrictions ---
        TupleQueryResult equivRestrictionRs = equivRestrictionFuture.join();
        while (equivRestrictionRs.hasNext()) {
            BindingSet sol = equivRestrictionRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String restrictionNode = sol.getValue("restriction").stringValue();
            String propIri = resource(sol, "prop");
            String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : formatIriWithPrefix(propIri);
            String restrictionType = sol.hasBinding("restrictionType") ? literal(sol, "restrictionType") : "some";
            String fillerIri = sol.hasBinding("filler") ? sol.getValue("filler").stringValue() : "";
            String fillerLabel = sol.hasBinding("fillerLabel") ? literal(sol, "fillerLabel") : formatIriWithPrefix(fillerIri);
            String cardinality = sol.hasBinding("card") ? literal(sol, "card") : "";
            String definition;
            if (!cardinality.isEmpty()) {
                definition = propLabel + " " + restrictionType + " " + cardinality + " " + fillerLabel;
            } else {
                definition = propLabel + " " + restrictionType + " " + fillerLabel;
            }
            axiom.put("id", restrictionNode);
            axiom.put("type", "EquivalentTo");
            axiom.put("definition", definition);
            axiom.put("isRestriction", "true");
            axiom.put("propertyIri", propIri);
            axiom.put("restrictionType", restrictionType);
            axiom.put("fillerIri", fillerIri);
            if (!cardinality.isEmpty()) {
                axiom.put("cardinality", cardinality);
            }
            equivAxioms.add(axiom);
        }
        
        // --- Process EquivalentClass anonymous expressions (intersection/union/complement/oneOf) ---
        TupleQueryResult equivAnonRs = equivAnonymousFuture.join();
        Map<String, List<String>> equivAnonMemberIris = new LinkedHashMap<>();
        Map<String, List<String>> equivAnonMemberLabels = new LinkedHashMap<>();
        Map<String, String> equivAnonExprType = new LinkedHashMap<>();
        while (equivAnonRs.hasNext()) {
            BindingSet sol = equivAnonRs.next();
            String bnode = sol.getValue("bnode").stringValue();
            String memberIri = resource(sol, "member");
            String memberLabel = sol.hasBinding("memberLabel") ? literal(sol, "memberLabel") : localName(memberIri);
            String exprType = sol.hasBinding("exprType") ? literal(sol, "exprType") : "";
            if (memberIri != null) {
                equivAnonMemberIris.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberIri);
                equivAnonMemberLabels.computeIfAbsent(bnode, k -> new ArrayList<>()).add(memberLabel);
                equivAnonExprType.putIfAbsent(bnode, exprType);
            }
        }
        for (Map.Entry<String, List<String>> entry : equivAnonMemberIris.entrySet()) {
            String bnode = entry.getKey();
            List<String> labels = equivAnonMemberLabels.get(bnode);
            if (labels == null || labels.isEmpty()) continue;
            String exprType = equivAnonExprType.getOrDefault(bnode, "");
            String definition = switch (exprType) {
                case "intersection" -> String.join(" and ", labels);
                case "union"        -> String.join(" or ", labels);
                case "complement"   -> "not " + labels.get(0);
                case "oneOf"        -> "{" + String.join(", ", labels) + "}";
                default             -> String.join(", ", labels);
            };
            Map<String, String> axiom = new LinkedHashMap<>();
            axiom.put("id", bnode);
            axiom.put("type", "EquivalentTo");
            axiom.put("definition", definition);
            axiom.put("isComplex", "true");
            axiom.put("expressionType", exprType);
            equivAxioms.add(axiom);
        }
        
        details.put("equivalentClassesAxioms", equivAxioms);
        
        // --- Process DisjointWith ---
        TupleQueryResult disjointRs = disjointFuture.join();
        List<Map<String, String>> disjointAxioms = new ArrayList<>();
        while (disjointRs.hasNext()) {
            BindingSet sol = disjointRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String disjointIri = resource(sol, "disjoint");
            if (disjointIri != null) {
                axiom.put("id", disjointIri);
                axiom.put("type", "DisjointWith");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(disjointIri));
                disjointAxioms.add(axiom);
            }
        }
        details.put("disjointClassesAxioms", disjointAxioms);
        
        // --- Process DisjointUnionOf + HasKey (merged) ---
        TupleQueryResult listAxiomsRs = listAxiomsFuture.join();
        Map<String, List<String>> disjointUnionGroups = new LinkedHashMap<>();
        Map<String, List<String>> hasKeyGroups = new LinkedHashMap<>();
        while (listAxiomsRs.hasNext()) {
            BindingSet sol = listAxiomsRs.next();
            String axType = sol.hasBinding("axType") ? literal(sol, "axType") : "";
            String listNode = sol.getValue("list").stringValue();
            String propIri = sol.getValue("prop").stringValue();
            if ("disjointUnion".equals(axType)) {
                disjointUnionGroups.computeIfAbsent(listNode, k -> new ArrayList<>()).add(propIri);
            } else if ("hasKey".equals(axType)) {
                hasKeyGroups.computeIfAbsent(listNode, k -> new ArrayList<>()).add(propIri);
            }
        }
        List<Map<String, Object>> disjointUnionAxioms = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : disjointUnionGroups.entrySet()) {
            String listNode = entry.getKey();
            List<String> members = entry.getValue();
            if (!members.isEmpty()) {
                Map<String, Object> axiom = new LinkedHashMap<>();
                axiom.put("id", listNode);
                axiom.put("type", "DisjointUnionOf");
                axiom.put("members", members);
                StringBuilder defBuilder = new StringBuilder();
                for (int i = 0; i < members.size(); i++) {
                    if (i > 0) defBuilder.append(", ");
                    defBuilder.append(localName(members.get(i)));
                }
                axiom.put("definition", defBuilder.toString());
                disjointUnionAxioms.add(axiom);
            }
        }
        details.put("disjointUnionAxioms", disjointUnionAxioms);
        
        List<Map<String, Object>> hasKeyAxioms = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : hasKeyGroups.entrySet()) {
            String listNode = entry.getKey();
            List<String> keyProperties = entry.getValue();
            if (!keyProperties.isEmpty()) {
                Map<String, Object> axiom = new LinkedHashMap<>();
                axiom.put("id", listNode);
                axiom.put("type", "HasKey");
                axiom.put("properties", keyProperties);
                StringBuilder defBuilder = new StringBuilder();
                for (int i = 0; i < keyProperties.size(); i++) {
                    if (i > 0) defBuilder.append(", ");
                    defBuilder.append(localName(keyProperties.get(i)));
                }
                axiom.put("definition", defBuilder.toString());
                hasKeyAxioms.add(axiom);
            }
        }
        details.put("hasKeyAxioms", hasKeyAxioms);
        
        // --- Process inferred equivalent classes ---
        TupleQueryResult inferredEquivRs = inferredEquivFuture.join();
        List<Map<String, String>> inferredEquivAxioms = new ArrayList<>();
        while (inferredEquivRs.hasNext()) {
            BindingSet sol = inferredEquivRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String equivIri = resource(sol, "equiv");
            if (equivIri != null) {
                axiom.put("id", equivIri);
                axiom.put("type", "EquivalentTo");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(equivIri));
                axiom.put("isInferred", "true");
                inferredEquivAxioms.add(axiom);
            }
        }
        details.put("inferredEquivalentClassesAxioms", inferredEquivAxioms);
        
        // --- Process inferred superclasses ---
        TupleQueryResult inferredSuperRs = inferredSuperFuture.join();
        List<Map<String, String>> inferredSubClassAxioms = new ArrayList<>();
        while (inferredSuperRs.hasNext()) {
            BindingSet sol = inferredSuperRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String superIri = resource(sol, "super");
            if (superIri != null) {
                axiom.put("id", superIri);
                axiom.put("type", "SubClassOf");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(superIri));
                axiom.put("isInferred", "true");
                inferredSubClassAxioms.add(axiom);
            }
        }
        details.put("inferredSubClassOfAxioms", inferredSubClassAxioms);
        
        // --- Process inferred disjoint classes ---
        TupleQueryResult inferredDisjointRs = inferredDisjointFuture.join();
        List<Map<String, String>> inferredDisjointAxioms = new ArrayList<>();
        while (inferredDisjointRs.hasNext()) {
            BindingSet sol = inferredDisjointRs.next();
            Map<String, String> axiom = new LinkedHashMap<>();
            String disjointIri = resource(sol, "disjoint");
            if (disjointIri != null) {
                axiom.put("id", disjointIri);
                axiom.put("type", "DisjointWith");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(disjointIri));
                axiom.put("isInferred", "true");
                inferredDisjointAxioms.add(axiom);
            }
        }
        details.put("inferredDisjointClassesAxioms", inferredDisjointAxioms);
        
        // --- Process GCI axioms ---
        // Group rows by subExpr; inline member data avoids second-query blank-node instability.
        TupleQueryResult gciRs = gciFuture.join();
        Map<String, Map<String, Object>> gciGroups = new LinkedHashMap<>();
        while (gciRs.hasNext()) {
            BindingSet sol = gciRs.next();
            String subExpr = sol.getValue("subExpr").stringValue();
            Map<String, Object> group = gciGroups.computeIfAbsent(subExpr, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                String sc = resource(sol, "superClass");
                String scLabel = sol.hasBinding("superClassLabel") ? literal(sol, "superClassLabel")
                        : (sc != null ? localName(sc) : "?");
                g.put("superClassLabel", scLabel);
                g.put("exprType", null);
                g.put("members", new ArrayList<String>());
                return g;
            });
            if (sol.hasBinding("exprType")) {
                if (group.get("exprType") == null) group.put("exprType", sol.getValue("exprType").stringValue());
                if (sol.hasBinding("member")) {
                    String memberIri = resource(sol, "member");
                    String memberLabel = sol.hasBinding("memberLabel") ? literal(sol, "memberLabel")
                            : (memberIri != null ? localName(memberIri) : null);
                    if (memberLabel != null) {
                        @SuppressWarnings("unchecked")
                        List<String> members = (List<String>) group.get("members");
                        if (!members.contains(memberLabel)) members.add(memberLabel);
                    }
                }
            }
        }
        List<Map<String, String>> generalClassAxioms = new ArrayList<>();
        Set<String> seenGciDefinitions = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> entry : gciGroups.entrySet()) {
            String subExpr = entry.getKey();
            Map<String, Object> group = entry.getValue();
            String superClassLabel = (String) group.get("superClassLabel");
            String exprType = (String) group.get("exprType");
            @SuppressWarnings("unchecked")
            List<String> members = (List<String>) group.get("members");
            List<String> sortedMembers = new ArrayList<>(members);
            java.util.Collections.sort(sortedMembers);
            String subManchester = null;
            if (exprType != null && !sortedMembers.isEmpty()) {
                if ("intersection".equals(exprType)) subManchester = String.join(" and ", sortedMembers);
                else if ("union".equals(exprType)) subManchester = String.join(" or ", sortedMembers);
                else if ("complement".equals(exprType) && sortedMembers.size() == 1) subManchester = "not " + sortedMembers.get(0);
            }
            String definition = (subManchester != null && !subManchester.isBlank())
                    ? "(" + subManchester + ") SubClassOf " + superClassLabel
                    : "GCA SubClassOf " + superClassLabel;
            if (seenGciDefinitions.contains(definition)) continue;
            seenGciDefinitions.add(definition);
            Map<String, String> axiom = new LinkedHashMap<>();
            axiom.put("id", subExpr);
            axiom.put("type", "GCI");
            axiom.put("definition", definition);
            generalClassAxioms.add(axiom);
        }
        details.put("generalClassAxioms", generalClassAxioms);

        // --- Process ancestor axioms ---
        TupleQueryResult ancestorRs = ancestorFuture.join();
        List<Map<String, String>> anonymousAncestorAxioms = new ArrayList<>();
        Set<String> seenAncestors = new LinkedHashSet<>();
        while (ancestorRs.hasNext()) {
            BindingSet sol = ancestorRs.next();
            String superIri = resourceOrBlank(sol, "super");
            if (superIri == null) continue;
            String ancestorKey = superIri + "|" + resource(sol, "ancestor");
            if (!seenAncestors.contains(ancestorKey)) {
                seenAncestors.add(ancestorKey);
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", superIri);
                axiom.put("type", "SubClassOf");
                String ancestorIri = resource(sol, "ancestor");
                if (ancestorIri != null) {
                    axiom.put("ancestorIri", ancestorIri);
                }
                boolean navigable = superIri.startsWith("http://") || superIri.startsWith("https://") || superIri.startsWith("urn:");
                axiom.put("navigable", String.valueOf(navigable));
                if (!navigable) {
                    String manchester = describeBlankNodeManchester(projectId, superIri);
                    if (manchester != null && !manchester.isBlank()) {
                        axiom.put("manchester", manchester);
                        axiom.put("definition", manchester);
                    } else {
                        axiom.put("definition", "Anonymous superclass");
                    }
                } else {
                    axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(superIri));
                }
                anonymousAncestorAxioms.add(axiom);
            }
        }
        details.put("anonymousAncestorAxioms", anonymousAncestorAxioms);
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] classDetails for {} completed in {}ms project={} (parallel)", localName(classIri), duration, projectId);
        return details;
    }

    /**
     * Merge SPARQL-only class-detail sections into an OWLAPI fast-path response.
     * Keeps asserted axioms from the in-memory model (~5ms) and supplements fields
     * the OWLAPI builder does not yet produce (GCIs, anonymous ancestors, inferred
     * axioms, hasKey, disjointUnion, multi-valued annotations).
     */
    public void enrichOwlApiClassDetails(String projectId, String classIri, Map<String, Object> details) {
        safeIri(classIri);
        if (details == null || details.isEmpty()) {
            return;
        }
        long startTime = System.currentTimeMillis();
        final Executor queryPool = queryExecutorFor(projectId);

        CompletableFuture<TupleQueryResult> annFuture = CompletableFuture.supplyAsync(() -> {
            String annQuery = PREFIXES + """
                SELECT ?prop ?value WHERE {
                  <%s> ?prop ?value .
                  FILTER(isLiteral(?value) || isIRI(?value))
                  {
                    ?prop a owl:AnnotationProperty .
                  } UNION {
                    VALUES ?prop {
                      rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy
                      owl:deprecated owl:versionInfo owl:backwardCompatibleWith
                      owl:incompatibleWith owl:priorVersion
                    }
                  }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, annQuery);
        }, queryPool);

        CompletableFuture<TupleQueryResult> disjointFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?disjoint ?label WHERE {
                  {
                    <%s> owl:disjointWith ?disjoint .
                  } UNION {
                    ?disjoint owl:disjointWith <%s> .
                  } UNION {
                    ?allDisjoint a owl:AllDisjointClasses ;
                                 owl:members ?list .
                    ?list rdf:rest*/rdf:first <%s> .
                    ?list rdf:rest*/rdf:first ?disjoint .
                    FILTER(?disjoint != <%s>)
                  }
                  FILTER(isIRI(?disjoint) && ?disjoint != <%s>)
                  OPTIONAL { ?disjoint rdfs:label ?label }
                }
                ORDER BY ?label
                """.formatted(classIri, classIri, classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture<TupleQueryResult> disjointUnionFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?list ?member WHERE {
                  <%s> owl:disjointUnionOf ?list .
                  ?list rdf:rest*/rdf:first ?member .
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture<TupleQueryResult> hasKeyFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?keyList ?prop WHERE {
                  <%s> owl:hasKey ?keyList .
                  ?keyList rdf:rest*/rdf:first ?prop .
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture<TupleQueryResult> inferredEquivFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?equiv ?label WHERE {
                  GRAPH <http://www.ontotext.com/inferred> {
                    <%s> owl:equivalentClass ?equiv .
                  }
                  FILTER NOT EXISTS {
                    GRAPH <http://www.ontotext.com/explicit> {
                      <%s> owl:equivalentClass ?equiv .
                    }
                  }
                  FILTER(isIRI(?equiv) && ?equiv != <%s>)
                  OPTIONAL { ?equiv rdfs:label ?label }
                }
                """.formatted(classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture<TupleQueryResult> inferredSuperFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?super ?label WHERE {
                  GRAPH <http://www.ontotext.com/inferred> {
                    <%s> rdfs:subClassOf ?super .
                  }
                  FILTER NOT EXISTS {
                    GRAPH <http://www.ontotext.com/explicit> {
                      <%s> rdfs:subClassOf ?super .
                    }
                  }
                  FILTER(isIRI(?super) && ?super != owl:Thing && ?super != <%s>)
                  OPTIONAL { ?super rdfs:label ?label }
                }
                """.formatted(classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture<TupleQueryResult> inferredDisjointFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?disjoint ?label WHERE {
                  GRAPH <http://www.ontotext.com/inferred> {
                    {
                      <%s> owl:disjointWith ?disjoint .
                    } UNION {
                      ?disjoint owl:disjointWith <%s> .
                    }
                  }
                  FILTER NOT EXISTS {
                    GRAPH <http://www.ontotext.com/explicit> {
                      {
                        <%s> owl:disjointWith ?disjoint .
                      } UNION {
                        ?disjoint owl:disjointWith <%s> .
                      }
                    }
                  }
                  FILTER(isIRI(?disjoint) && ?disjoint != <%s>)
                  OPTIONAL { ?disjoint rdfs:label ?label }
                }
                """.formatted(classIri, classIri, classIri, classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture<TupleQueryResult> gciFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT ?subExpr ?superClass ?superClassLabel ?exprType ?member ?memberLabel WHERE {
                  ?subExpr rdfs:subClassOf ?superClass .
                  FILTER(isBlank(?subExpr))
                  {
                    FILTER(?superClass = <%s>)
                  } UNION {
                    ?subExpr (rdf:first|rdf:rest|owl:intersectionOf|owl:unionOf|owl:complementOf|owl:someValuesFrom|owl:allValuesFrom|owl:onClass)+ <%s> .
                  }
                  OPTIONAL { ?superClass rdfs:label ?superClassLabel }
                  OPTIONAL {
                    {
                      { ?subExpr owl:intersectionOf ?list . BIND("intersection" AS ?exprType) }
                      UNION
                      { ?subExpr owl:unionOf ?list . BIND("union" AS ?exprType) }
                      ?list rdf:rest*/rdf:first ?member .
                      FILTER(isIRI(?member))
                    }
                    UNION
                    {
                      ?subExpr owl:complementOf ?member . BIND("complement" AS ?exprType)
                      FILTER(isIRI(?member))
                    }
                    OPTIONAL { ?member rdfs:label ?memberLabel }
                  }
                }
                LIMIT 500
                """.formatted(classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture<TupleQueryResult> ancestorFuture = CompletableFuture.supplyAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?ancestor ?super ?label WHERE {
                  <%s> rdfs:subClassOf+ ?ancestor .
                  ?ancestor rdfs:subClassOf ?super .
                  FILTER(isBlank(?super) || (?super != owl:Thing && ?super != <%s>))
                  OPTIONAL { ?super rdfs:label ?label }
                }
                LIMIT 500
                """.formatted(classIri, classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture.allOf(
                annFuture, disjointFuture, disjointUnionFuture, hasKeyFuture,
                inferredEquivFuture, inferredSuperFuture, inferredDisjointFuture,
                gciFuture, ancestorFuture
        ).join();

        TupleQueryResult annRs = annFuture.join();
        Map<String, List<String>> annotations = AnnotationValueCollector.newMap();
        String label = null;
        while (annRs.hasNext()) {
            BindingSet sol = annRs.next();
            String propIri = resource(sol, "prop");
            if (propIri != null && sol.hasBinding("value")) {
                Value valueNode = sol.getValue("value");
                String value = valueNode.isLiteral() ? valueNode.stringValue() : valueNode.toString();
                AnnotationValueCollector.add(annotations, propIri, value);
                if (label == null && propIri.endsWith("#label")) {
                    label = value;
                }
            }
        }
        details.put("label", label != null ? label : localName(classIri));
        details.put("annotations", annotations);

        TupleQueryResult disjointRs = disjointFuture.join();
        List<Map<String, String>> disjointAxioms = new ArrayList<>();
        while (disjointRs.hasNext()) {
            BindingSet sol = disjointRs.next();
            String disjointIri = resource(sol, "disjoint");
            if (disjointIri != null) {
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", disjointIri);
                axiom.put("type", "DisjointWith");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(disjointIri));
                disjointAxioms.add(axiom);
            }
        }
        details.put("disjointClassesAxioms", disjointAxioms);

        TupleQueryResult disjointUnionRs = disjointUnionFuture.join();
        Map<String, List<String>> disjointUnionGroups = new LinkedHashMap<>();
        while (disjointUnionRs.hasNext()) {
            BindingSet sol = disjointUnionRs.next();
            String listNode = sol.getValue("list").stringValue();
            String memberIri = sol.getValue("member").stringValue();
            disjointUnionGroups.computeIfAbsent(listNode, k -> new ArrayList<>()).add(memberIri);
        }
        List<Map<String, Object>> disjointUnionAxioms = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : disjointUnionGroups.entrySet()) {
            List<String> members = entry.getValue();
            if (!members.isEmpty()) {
                Map<String, Object> axiom = new LinkedHashMap<>();
                axiom.put("id", entry.getKey());
                axiom.put("type", "DisjointUnionOf");
                axiom.put("members", members);
                StringBuilder defBuilder = new StringBuilder();
                for (int i = 0; i < members.size(); i++) {
                    if (i > 0) defBuilder.append(", ");
                    defBuilder.append(localName(members.get(i)));
                }
                axiom.put("definition", defBuilder.toString());
                disjointUnionAxioms.add(axiom);
            }
        }
        details.put("disjointUnionAxioms", disjointUnionAxioms);

        TupleQueryResult hasKeyRs = hasKeyFuture.join();
        Map<String, List<String>> hasKeyGroups = new LinkedHashMap<>();
        while (hasKeyRs.hasNext()) {
            BindingSet sol = hasKeyRs.next();
            String listNode = sol.getValue("keyList").stringValue();
            String propIri = sol.getValue("prop").stringValue();
            hasKeyGroups.computeIfAbsent(listNode, k -> new ArrayList<>()).add(propIri);
        }
        List<Map<String, Object>> hasKeyAxioms = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : hasKeyGroups.entrySet()) {
            List<String> keyProperties = entry.getValue();
            if (!keyProperties.isEmpty()) {
                Map<String, Object> axiom = new LinkedHashMap<>();
                axiom.put("id", entry.getKey());
                axiom.put("type", "HasKey");
                axiom.put("properties", keyProperties);
                StringBuilder defBuilder = new StringBuilder();
                for (int i = 0; i < keyProperties.size(); i++) {
                    if (i > 0) defBuilder.append(", ");
                    defBuilder.append(localName(keyProperties.get(i)));
                }
                axiom.put("definition", defBuilder.toString());
                hasKeyAxioms.add(axiom);
            }
        }
        details.put("hasKeyAxioms", hasKeyAxioms);

        TupleQueryResult inferredEquivRs = inferredEquivFuture.join();
        List<Map<String, String>> inferredEquivAxioms = new ArrayList<>();
        while (inferredEquivRs.hasNext()) {
            BindingSet sol = inferredEquivRs.next();
            String equivIri = resource(sol, "equiv");
            if (equivIri != null) {
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", equivIri);
                axiom.put("type", "EquivalentTo");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(equivIri));
                axiom.put("isInferred", "true");
                inferredEquivAxioms.add(axiom);
            }
        }
        details.put("inferredEquivalentClassesAxioms", inferredEquivAxioms);

        TupleQueryResult inferredSuperRs = inferredSuperFuture.join();
        List<Map<String, String>> inferredSubClassAxioms = new ArrayList<>();
        while (inferredSuperRs.hasNext()) {
            BindingSet sol = inferredSuperRs.next();
            String superIri = resource(sol, "super");
            if (superIri != null) {
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", superIri);
                axiom.put("type", "SubClassOf");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(superIri));
                axiom.put("isInferred", "true");
                inferredSubClassAxioms.add(axiom);
            }
        }
        details.put("inferredSubClassOfAxioms", inferredSubClassAxioms);

        TupleQueryResult inferredDisjointRs = inferredDisjointFuture.join();
        List<Map<String, String>> inferredDisjointAxioms = new ArrayList<>();
        while (inferredDisjointRs.hasNext()) {
            BindingSet sol = inferredDisjointRs.next();
            String disjointIri = resource(sol, "disjoint");
            if (disjointIri != null) {
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", disjointIri);
                axiom.put("type", "DisjointWith");
                axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(disjointIri));
                axiom.put("isInferred", "true");
                inferredDisjointAxioms.add(axiom);
            }
        }
        details.put("inferredDisjointClassesAxioms", inferredDisjointAxioms);

        TupleQueryResult gciRs = gciFuture.join();
        Map<String, Map<String, Object>> gciGroups = new LinkedHashMap<>();
        while (gciRs.hasNext()) {
            BindingSet sol = gciRs.next();
            String subExpr = sol.getValue("subExpr").stringValue();
            Map<String, Object> group = gciGroups.computeIfAbsent(subExpr, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                String sc = resource(sol, "superClass");
                String scLabel = sol.hasBinding("superClassLabel") ? literal(sol, "superClassLabel")
                        : (sc != null ? localName(sc) : "?");
                g.put("superClassLabel", scLabel);
                g.put("exprType", null);
                g.put("members", new ArrayList<String>());
                return g;
            });
            if (sol.hasBinding("exprType")) {
                if (group.get("exprType") == null) group.put("exprType", sol.getValue("exprType").stringValue());
                if (sol.hasBinding("member")) {
                    String memberIri = resource(sol, "member");
                    String memberLabel = sol.hasBinding("memberLabel") ? literal(sol, "memberLabel")
                            : (memberIri != null ? localName(memberIri) : null);
                    if (memberLabel != null) {
                        @SuppressWarnings("unchecked")
                        List<String> members = (List<String>) group.get("members");
                        if (!members.contains(memberLabel)) members.add(memberLabel);
                    }
                }
            }
        }
        List<Map<String, String>> generalClassAxioms = new ArrayList<>();
        Set<String> seenGciDefinitions = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> entry : gciGroups.entrySet()) {
            String subExpr = entry.getKey();
            Map<String, Object> group = entry.getValue();
            String superClassLabel = (String) group.get("superClassLabel");
            String exprType = (String) group.get("exprType");
            @SuppressWarnings("unchecked")
            List<String> members = (List<String>) group.get("members");
            List<String> sortedMembers = new ArrayList<>(members);
            java.util.Collections.sort(sortedMembers);
            String subManchester = null;
            if (exprType != null && !sortedMembers.isEmpty()) {
                if ("intersection".equals(exprType)) subManchester = String.join(" and ", sortedMembers);
                else if ("union".equals(exprType)) subManchester = String.join(" or ", sortedMembers);
                else if ("complement".equals(exprType) && sortedMembers.size() == 1) subManchester = "not " + sortedMembers.get(0);
            }
            String definition = (subManchester != null && !subManchester.isBlank())
                    ? "(" + subManchester + ") SubClassOf " + superClassLabel
                    : "GCA SubClassOf " + superClassLabel;
            if (seenGciDefinitions.contains(definition)) continue;
            seenGciDefinitions.add(definition);
            Map<String, String> axiom = new LinkedHashMap<>();
            axiom.put("id", subExpr);
            axiom.put("type", "GCI");
            axiom.put("definition", definition);
            generalClassAxioms.add(axiom);
        }
        details.put("generalClassAxioms", generalClassAxioms);

        TupleQueryResult ancestorRs = ancestorFuture.join();
        List<Map<String, String>> anonymousAncestorAxioms = new ArrayList<>();
        Set<String> seenAncestors = new LinkedHashSet<>();
        while (ancestorRs.hasNext()) {
            BindingSet sol = ancestorRs.next();
            String superIri = resourceOrBlank(sol, "super");
            if (superIri == null) continue;
            String ancestorKey = superIri + "|" + resource(sol, "ancestor");
            if (!seenAncestors.contains(ancestorKey)) {
                seenAncestors.add(ancestorKey);
                Map<String, String> axiom = new LinkedHashMap<>();
                axiom.put("id", superIri);
                axiom.put("type", "SubClassOf");
                String ancestorIri = resource(sol, "ancestor");
                if (ancestorIri != null) {
                    axiom.put("ancestorIri", ancestorIri);
                }
                boolean navigable = superIri.startsWith("http://") || superIri.startsWith("https://") || superIri.startsWith("urn:");
                axiom.put("navigable", String.valueOf(navigable));
                if (!navigable) {
                    String manchester = describeBlankNodeManchester(projectId, superIri);
                    if (manchester != null && !manchester.isBlank()) {
                        axiom.put("manchester", manchester);
                        axiom.put("definition", manchester);
                    } else {
                        axiom.put("definition", "Anonymous superclass");
                    }
                } else {
                    axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(superIri));
                }
                anonymousAncestorAxioms.add(axiom);
            }
        }
        details.put("anonymousAncestorAxioms", anonymousAncestorAxioms);

        log.info("[PERF] enrichOwlApiClassDetails for {} completed in {}ms project={}",
                localName(classIri), System.currentTimeMillis() - startTime, projectId);
    }

    /**
     * Get all instances (individuals) of a given class.
     * Returns both asserted and inferred instances.
     * OPTIMIZED: Cached + combined into single SPARQL query with BIND for isInferred flag.
     */
    @Cacheable(value = "classInstances", key = "#projectId + '_' + #classIri")
    public List<Map<String, Object>> getClassInstances(String projectId, String classIri) {
        safeIri(classIri);
        long startTime = System.currentTimeMillis();
        List<Map<String, Object>> instances = new ArrayList<>();
        Set<String> seenIndividuals = new LinkedHashSet<>();
        
        // Asserted class assertions only (Fuseki/Jena). Inferred instances come from OWLAPI
        // reasoner path (desktop / fast-open cache) or after classify via reasoner endpoints.
        String combinedQuery = PREFIXES + """
            SELECT DISTINCT ?individual ?label WHERE {
              ?individual a <%s> .
              FILTER(isIRI(?individual))
              OPTIONAL { ?individual rdfs:label ?label }
            }
            ORDER BY ?label
            """.formatted(classIri);
        
        TupleQueryResult rs = datasetService.execSelect(projectId, combinedQuery);
        
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String individualIri = resource(sol, "individual");
            if (individualIri != null && !seenIndividuals.contains(individualIri)) {
                seenIndividuals.add(individualIri);
                Map<String, Object> individual = new LinkedHashMap<>();
                individual.put("id", individualIri);
                individual.put("label", sol.hasBinding("label") ? literal(sol, "label") : localName(individualIri));
                individual.put("isInferred", false);
                
                List<String> types = new ArrayList<>();
                types.add(classIri);
                individual.put("types", types);
                
                instances.add(individual);
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] getClassInstances for {} loaded {} instances in {}ms project={}", localName(classIri), instances.size(), duration, projectId);
        return instances;
    }

    /**
     * Get per-class instance counts (asserted and inferred).
     */
    /**
     * Get per-class instance counts.
     * OPTIMIZED: Cached + simplified query (skip inferred graph for speed).
     */
    @Cacheable(value = "classInstanceCounts", key = "#projectId")
    public Map<String, Map<String, Integer>> getClassInstanceCounts(String projectId) {
        long startTime = System.currentTimeMillis();
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();

        // OPTIMIZED: Single simple query instead of querying explicit/inferred graphs separately
        // The explicit/inferred graph split is a GraphDB-specific feature that's very slow on large ontologies
        String query = PREFIXES + """
            SELECT ?class (COUNT(DISTINCT ?individual) AS ?count) WHERE {
              ?individual a ?class .
              FILTER(isIRI(?class))
              FILTER(?class != owl:NamedIndividual)
              FILTER(?class != owl:Class)
              FILTER(?class != owl:Thing)
            }
            GROUP BY ?class
            HAVING (COUNT(DISTINCT ?individual) > 0)
            """;
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String classIri = resource(sol, "class");
                if (classIri != null) {
                    int cnt = literalToInt(sol, "count");
                    Map<String, Integer> entry = new LinkedHashMap<>();
                    entry.put("direct", cnt);
                    entry.put("inferred", 0);
                    entry.put("total", cnt);
                    counts.put(classIri, entry);
                }
            }
        } catch (Exception e) {
            log.warn("[PERF] Instance counts query failed (non-critical): {}", e.getMessage());
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ [PERF] Loaded instance counts for {} classes in {}ms", counts.size(), duration);
        return counts;
    }

    /**
     * Get detailed information about an individual
     */
    public Map<String, Object> getIndividualDetails(String projectId, String individualIri) {
        safeIri(individualIri);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", individualIri);
        
        // Get label
        String labelQuery = PREFIXES + """
            SELECT ?label WHERE {
              <%s> rdfs:label ?label
            } LIMIT 1
            """.formatted(individualIri);
        TupleQueryResult labelRs = datasetService.execSelect(projectId, labelQuery);
        if (labelRs.hasNext()) {
            BindingSet labelSol = labelRs.next();
            details.put("label", literal(labelSol, "label"));
        } else {
            details.put("label", localName(individualIri));
        }
        
        // Get types
        String typesQuery = PREFIXES + """
            SELECT DISTINCT ?type ?typeLabel WHERE {
              <%s> a ?type .
              FILTER(isIRI(?type) && ?type != owl:NamedIndividual)
              OPTIONAL { ?type rdfs:label ?typeLabel }
            }
            """.formatted(individualIri);
        TupleQueryResult typesRs = datasetService.execSelect(projectId, typesQuery);
        List<String> types = new ArrayList<>();
        while (typesRs.hasNext()) {
            BindingSet sol = typesRs.next();
            String typeIri = resource(sol, "type");
            if (typeIri != null) {
                types.add(typeIri);
            }
        }
        details.put("types", types);
        
        // Get annotations
        String annQuery = PREFIXES + """
            SELECT ?prop ?value WHERE {
              <%s> ?prop ?value .
              FILTER(isLiteral(?value) || isIRI(?value))
              {
                ?prop a owl:AnnotationProperty .
              } UNION {
                VALUES ?prop {
                  rdfs:label rdfs:comment rdfs:seeAlso rdfs:isDefinedBy
                  owl:deprecated owl:versionInfo owl:backwardCompatibleWith
                  owl:incompatibleWith owl:priorVersion
                }
              }
            }
            """.formatted(individualIri);
        TupleQueryResult annRs = datasetService.execSelect(projectId, annQuery);
        Map<String, List<String>> annotations = AnnotationValueCollector.newMap();
        while (annRs.hasNext()) {
            BindingSet sol = annRs.next();
            String propIri = resource(sol, "prop");
            if (propIri != null && sol.hasBinding("value")) {
                String value = sol.getValue("value").stringValue();
                AnnotationValueCollector.add(annotations, propIri, value);
            }
        }
        details.put("annotations", annotations);
        
        // Get property assertions
        String propsQuery = PREFIXES + """
            SELECT ?prop ?obj ?objLabel WHERE {
              <%s> ?prop ?obj .
              FILTER(?prop != rdf:type)
              FILTER(?prop != owl:sameAs)
              FILTER(?prop != owl:differentFrom)
              FILTER NOT EXISTS { ?prop a owl:AnnotationProperty }
              OPTIONAL { ?obj rdfs:label ?objLabel }
            }
            """.formatted(individualIri);
        TupleQueryResult propsRs = datasetService.execSelect(projectId, propsQuery);
        List<Map<String, Object>> propertyAssertions = new ArrayList<>();
        while (propsRs.hasNext()) {
            BindingSet sol = propsRs.next();
            String propIri = resource(sol, "prop");
            if (propIri != null) {
                Map<String, Object> assertion = new LinkedHashMap<>();
                assertion.put("id", "assertion-" + propertyAssertions.size());
                assertion.put("propertyIri", propIri);
                assertion.put("propertyLabel", localName(propIri));
                
                Value objValue = sol.getValue("obj");
                if (objValue.isIRI()) {
                    assertion.put("targetIri", objValue.stringValue());
                    assertion.put("targetLabel", sol.hasBinding("objLabel") ? literal(sol, "objLabel") : localName(objValue.stringValue()));
                    assertion.put("isObjectProperty", true);
                } else {
                    assertion.put("targetLiteral", objValue.stringValue());
                    assertion.put("isObjectProperty", false);
                }
                
                propertyAssertions.add(assertion);
            }
        }

        // Get negative property assertions (OWL2 NegativePropertyAssertion)
        String negativePropsQuery = PREFIXES + """
            SELECT ?prop ?targetInd ?targetIndLabel ?targetValue WHERE {
              ?npa a owl:NegativePropertyAssertion ;
                   owl:sourceIndividual <%s> ;
                   owl:assertionProperty ?prop .
              OPTIONAL {
                ?npa owl:targetIndividual ?targetInd .
                OPTIONAL { ?targetInd rdfs:label ?targetIndLabel }
              }
              OPTIONAL { ?npa owl:targetValue ?targetValue . }
            }
            """.formatted(individualIri);
        TupleQueryResult negRs = datasetService.execSelect(projectId, negativePropsQuery);
        while (negRs.hasNext()) {
            BindingSet sol = negRs.next();
            String propIri = resource(sol, "prop");
            if (propIri == null) continue;

            Map<String, Object> assertion = new LinkedHashMap<>();
            assertion.put("id", "neg-assertion-" + propertyAssertions.size());
            assertion.put("propertyIri", propIri);
            assertion.put("propertyLabel", localName(propIri));
            assertion.put("isNegative", true);

            Value targetInd = sol.getValue("targetInd");
            Value targetValue = sol.getValue("targetValue");
            if (targetInd != null && targetInd.isIRI()) {
                assertion.put("targetIri", targetInd.stringValue());
                assertion.put("targetLabel", sol.hasBinding("targetIndLabel") ? literal(sol, "targetIndLabel") : localName(targetInd.stringValue()));
                assertion.put("isObjectProperty", true);
            } else if (targetValue != null) {
                assertion.put("targetLiteral", targetValue.stringValue());
                assertion.put("isObjectProperty", false);
            } else {
                // Skip malformed NPA without a target
                continue;
            }

            propertyAssertions.add(assertion);
        }
        details.put("propertyAssertions", propertyAssertions);
        
        // Get sameAs
        String sameAsQuery = PREFIXES + """
            SELECT ?same WHERE {
              <%s> owl:sameAs ?same .
            }
            """.formatted(individualIri);
        TupleQueryResult sameRs = datasetService.execSelect(projectId, sameAsQuery);
        List<String> sameAs = new ArrayList<>();
        while (sameRs.hasNext()) {
            BindingSet sol = sameRs.next();
            String same = resource(sol, "same");
            if (same != null) {
                sameAs.add(same);
            }
        }
        details.put("sameIndividualAs", sameAs);
        
        // Get differentFrom
        String diffQuery = PREFIXES + """
            SELECT ?diff WHERE {
              <%s> owl:differentFrom ?diff .
            }
            """.formatted(individualIri);
        TupleQueryResult diffRs = datasetService.execSelect(projectId, diffQuery);
        List<String> differentFrom = new ArrayList<>();
        while (diffRs.hasNext()) {
            BindingSet sol = diffRs.next();
            String diff = resource(sol, "diff");
            if (diff != null) {
                differentFrom.add(diff);
            }
        }
        details.put("differentIndividualFrom", differentFrom);
        
        return details;
    }

    public Map<String, Object> getOntologySchema(String projectId) {
        Map<String, Object> schema = new LinkedHashMap<>();
        
        // Get all classes
        String classesQuery = PREFIXES + """
            SELECT DISTINCT ?class WHERE {
              ?class a owl:Class .
              FILTER(isIRI(?class))
              FILTER(?class != owl:Thing && ?class != owl:Nothing)
            }
            ORDER BY ?class
            LIMIT 1000
            """;
        TupleQueryResult classesResult = datasetService.execSelect(projectId, classesQuery);
        List<String> classes = new ArrayList<>();
        while (classesResult.hasNext()) {
            BindingSet sol = classesResult.next();
            String cls = resource(sol, "class");
            if (cls != null) {
                classes.add(cls);
            }
        }
        schema.put("classes", classes);
        
        // Get all object properties
        String objectPropsQuery = PREFIXES + """
            SELECT DISTINCT ?prop WHERE {
              ?prop a owl:ObjectProperty .
              FILTER(isIRI(?prop))
            }
            ORDER BY ?prop
            LIMIT 1000
            """;
        TupleQueryResult objResult = datasetService.execSelect(projectId, objectPropsQuery);
        List<String> objectProperties = new ArrayList<>();
        while (objResult.hasNext()) {
            BindingSet sol = objResult.next();
            String prop = resource(sol, "prop");
            if (prop != null) {
                objectProperties.add(prop);
            }
        }
        schema.put("objectProperties", objectProperties);
        
        // Get all data properties
        String dataPropsQuery = PREFIXES + """
            SELECT DISTINCT ?prop WHERE {
              ?prop a owl:DatatypeProperty .
              FILTER(isIRI(?prop))
            }
            ORDER BY ?prop
            LIMIT 1000
            """;
        TupleQueryResult dataResult = datasetService.execSelect(projectId, dataPropsQuery);
        List<String> dataProperties = new ArrayList<>();
        while (dataResult.hasNext()) {
            BindingSet sol = dataResult.next();
            String prop = resource(sol, "prop");
            if (prop != null) {
                dataProperties.add(prop);
            }
        }
        schema.put("dataProperties", dataProperties);
        
        return schema;
    }
}


