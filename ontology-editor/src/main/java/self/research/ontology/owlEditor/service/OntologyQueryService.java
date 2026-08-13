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
import java.util.Collections;
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

    private static final ExecutorService QUERY_POOL = Executors.newFixedThreadPool(64);

    private static final ExecutorService LARGE_QUERY_POOL = Executors.newFixedThreadPool(4);

    private Executor queryExecutorFor(String projectId) {
        return datasetService.isKnownLargeProject(projectId) ? LARGE_QUERY_POOL : QUERY_POOL;
    }

    private static <T> CompletableFuture<T> queryAsync(java.util.function.Supplier<T> supplier, Executor executor) {
        return CompletableFuture.supplyAsync(SparqlQueryContext.wrap(supplier), executor);
    }

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        """;

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
        return "";
    }

    @Cacheable(value = "topLevelClasses", key = "#projectId + '_statusCount_' + T(self.research.ontology.owlEditor.service.SparqlQueryContext).cacheKeyComponent()", sync = true)
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

    @Cacheable(value = "topLevelClasses", key = "#projectId + '_' + #limit + '_' + T(self.research.ontology.owlEditor.service.SparqlQueryContext).cacheKeyComponent()",
               unless = "#result != null && #result.isEmpty()")
    public List<OntologyDto.TreeNode> topLevelClasses(String projectId, int limit) {
        long startTime = System.currentTimeMillis();

        String qsCtxUserId = SparqlQueryContext.getUserId();
        boolean hasDraft = qsCtxUserId != null
                && datasetService.hasActiveDraftOverlay(projectId, qsCtxUserId);

        List<OntologyDto.TreeNode> mongoHit = hasDraft ? null : topLevelCacheService.get(projectId, limit);
        if (mongoHit != null && !mongoHit.isEmpty()) {
            log.info("[PERF] Top-level classes served from MongoDB cache for project={} in {}ms",
                    projectId, System.currentTimeMillis() - startTime);
            return mongoHit;
        }

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

        long tripleCount = datasetService.getGraphTripleCount(projectId);
        boolean largeGraph = tripleCount < 0 || tripleCount >= 500_000L;
        List<OntologyDto.TreeNode> phase1;
        if (p1Iris.isEmpty()) {
            phase1 = java.util.Collections.emptyList();
        } else {
            String valuesBlock = p1Iris.stream()
                    .map(iri -> "<" + iri + ">")
                    .collect(java.util.stream.Collectors.joining(" "));
            String hasChildrenExpr = largeGraph ? """
                  ?child rdfs:subClassOf ?c .
                  FILTER(?child != ?c && isIRI(?child))
                  FILTER NOT EXISTS { ?child owl:deprecated true }
                """ : """
                  { ?child rdfs:subClassOf ?c . }
                  UNION { ?child owl:equivalentClass ?hcExpr . ?hcExpr owl:intersectionOf/rdf:rest*/rdf:first ?c . }
                  UNION { ?child rdfs:subClassOf ?hcExpr . ?hcExpr owl:intersectionOf/rdf:rest*/rdf:first ?c . }
                  FILTER(?child != ?c && isIRI(?child))
                """;
            String phase1bQuery = PREFIXES + """
                SELECT ?c ?label ?description
                (EXISTS {
                  %s
                } AS ?hasChildren)
                WHERE {
                  VALUES ?c { %s }
                  OPTIONAL { ?c rdfs:label ?label }
                  OPTIONAL { ?c rdfs:comment ?description }
                }
                """.formatted(hasChildrenExpr, valuesBlock);
            phase1 = mapTreeNodes(projectId, phase1bQuery, null);
        }
        long p1Duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] Top-level classes phase 1 complete: {} results in {}ms", phase1.size(), p1Duration);

        Set<String> phase1Iris = phase1.stream()
                .map(OntologyDto.TreeNode::getId)
                .collect(java.util.stream.Collectors.toSet());

        boolean knownLarge = datasetService.isKnownLargeProject(projectId);
        if (p1aDuration > 10000 && !phase1.isEmpty()) {
            log.info("[PERF] Skipping orphan scan for project={} (p1aDuration={}ms — cold Fuseki, phase1={})",
                     projectId, p1aDuration, phase1.size());
            List<OntologyDto.TreeNode> merged = new java.util.ArrayList<>(phase1);
            merged.sort(java.util.Comparator.comparing(n -> n.getLabel() != null ? n.getLabel().toLowerCase() : n.getId()));
            List<OntologyDto.TreeNode> result = merged.size() > limit ? merged.subList(0, limit) : merged;
            enrichWithEquivalentClasses(projectId, result);

            final List<OntologyDto.TreeNode> toStore = new java.util.ArrayList<>(result);
            final int finalLimit = limit;
            if (!toStore.isEmpty() && !hasDraft) {
                CompletableFuture.runAsync(() -> topLevelCacheService.put(projectId, toStore, finalLimit));
            }
            return result;
        }

        String allClassesQueryStr = PREFIXES + """
            SELECT DISTINCT ?c WHERE {
              ?c a owl:Class .
              FILTER(isIRI(?c) && ?c != <http://www.w3.org/2002/07/owl#Thing>)
              FILTER NOT EXISTS { ?c owl:deprecated true }
            }
            """;
        String hasParentQueryStr = PREFIXES + """
            SELECT DISTINCT ?c WHERE {
              ?c rdfs:subClassOf ?p .
              FILTER(isIRI(?p) && ?p != <http://www.w3.org/2002/07/owl#Thing> && ?p != ?c)
            }
            """;

        CompletableFuture<Set<String>> allClassesFuture = queryAsync(SparqlQueryContext.wrap(() -> {
            Set<String> s = new java.util.HashSet<>();
            TupleQueryResult r = datasetService.execSelect(projectId, allClassesQueryStr);
            while (r.hasNext()) { Value v = r.next().getValue("c"); if (v != null) s.add(v.stringValue()); }
            return s;
        }), QUERY_POOL);

        CompletableFuture<Set<String>> hasParentFuture = queryAsync(SparqlQueryContext.wrap(() -> {
            Set<String> s = new java.util.HashSet<>();
            TupleQueryResult r = datasetService.execSelect(projectId, hasParentQueryStr);
            while (r.hasNext()) { Value v = r.next().getValue("c"); if (v != null) s.add(v.stringValue()); }
            return s;
        }), QUERY_POOL);

        Set<String> allClassIris;
        Set<String> hasParentIris;
        try {
            allClassIris = allClassesFuture.get(50, java.util.concurrent.TimeUnit.SECONDS);
            hasParentIris = hasParentFuture.get(50, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException | InterruptedException | java.util.concurrent.ExecutionException e) {
            log.warn("[PERF] Orphan scan A/B timed out for project={} after 50s — returning phase1 only: {}", projectId, e.getMessage());
            allClassesFuture.cancel(true);
            hasParentFuture.cancel(true);
            List<OntologyDto.TreeNode> fallback = new java.util.ArrayList<>(phase1);
            fallback.sort(java.util.Comparator.comparing(n -> n.getLabel() != null ? n.getLabel().toLowerCase() : n.getId()));
            List<OntologyDto.TreeNode> fallbackResult = fallback.size() > limit ? fallback.subList(0, limit) : fallback;
            enrichWithEquivalentClasses(projectId, fallbackResult);
            return fallbackResult;
        }
        long afterHasParent = System.currentTimeMillis() - startTime;
        log.info("[PERF] Parallel A+B scan: allClasses={} hasParent={} in {}ms (parallel wall-clock)",
                allClassIris.size(), hasParentIris.size(), afterHasParent - p1Duration);

        allClassIris.removeAll(hasParentIris);
        allClassIris.removeAll(phase1Iris);
        List<String> orphanIris = new java.util.ArrayList<>(allClassIris);
        orphanIris.sort(java.util.Comparator.naturalOrder());
        log.info("[PERF] Java-subtraction orphan candidates: {}", orphanIris.size());

        if (!orphanIris.isEmpty() && orphanIris.size() <= 5000) {
            String complexCheckValues = orphanIris.stream()
                    .map(iri -> "<" + iri + ">")
                    .collect(java.util.stream.Collectors.joining(" "));
            String complexParentCheckQuery = PREFIXES + """
                SELECT DISTINCT ?c WHERE {
                  VALUES ?c { %s }
                  {
                    ?c rdfs:subClassOf ?anon .
                    ?anon owl:intersectionOf/rdf:rest*/rdf:first ?p .
                    FILTER(isIRI(?p) && ?p != <http://www.w3.org/2002/07/owl#Thing> && ?p != ?c)
                  } UNION {
                    ?c owl:equivalentClass ?ec .
                    ?ec owl:intersectionOf/rdf:rest*/rdf:first ?p .
                    FILTER(isIRI(?p) && ?p != <http://www.w3.org/2002/07/owl#Thing> && ?p != ?c)
                  } UNION {
                    ?p owl:unionOf/rdf:rest*/rdf:first ?c .
                    FILTER(isIRI(?p) && ?p != ?c)
                  }
                }
                """.formatted(complexCheckValues);
            try {
                Set<String> hasComplexParent = new java.util.HashSet<>();
                TupleQueryResult complexRs = datasetService.execSelect(projectId, complexParentCheckQuery);
                while (complexRs.hasNext()) {
                    Value v = complexRs.next().getValue("c");
                    if (v != null) hasComplexParent.add(v.stringValue());
                }
                if (!hasComplexParent.isEmpty()) {
                    orphanIris.removeAll(hasComplexParent);
                    log.info("[PERF] Complex-expression check: removed {} false orphans, {} true orphans remain",
                            hasComplexParent.size(), orphanIris.size());
                }
            } catch (Exception e) {
                log.warn("[PERF] Complex parent check failed for project={}, proceeding without it: {}", projectId, e.getMessage());
            }
        } else if (orphanIris.size() > 5000) {
            log.info("[PERF] Skipping complex parent check: {} candidates exceeds safety threshold (5000)", orphanIris.size());
        }

        if (orphanIris.size() > limit) orphanIris = orphanIris.subList(0, limit);

        List<OntologyDto.TreeNode> orphans = java.util.Collections.emptyList();
        if (!orphanIris.isEmpty()) {
            String valuesBlock = orphanIris.stream()
                    .map(iri -> "<" + iri + ">")
                    .collect(java.util.stream.Collectors.joining(" "));
            String orphanHasChildrenExpr = largeGraph ? """
                  ?child rdfs:subClassOf ?c .
                  FILTER(?child != ?c && isIRI(?child))
                  FILTER NOT EXISTS { ?child owl:deprecated true }
                """ : """
                  { ?child rdfs:subClassOf ?c . }
                  UNION { ?child owl:equivalentClass ?hcExpr . ?hcExpr owl:intersectionOf/rdf:rest*/rdf:first ?c . }
                  UNION { ?child rdfs:subClassOf ?hcExpr . ?hcExpr owl:intersectionOf/rdf:rest*/rdf:first ?c . }
                  FILTER(?child != ?c && isIRI(?child))
                """;
            String hydrationQuery = PREFIXES + """
                SELECT ?c ?label ?description
                (EXISTS {
                  %s
                } AS ?hasChildren)
                WHERE {
                  VALUES ?c { %s }
                  OPTIONAL { ?c rdfs:label ?label }
                  OPTIONAL { ?c rdfs:comment ?description }
                }
                """.formatted(orphanHasChildrenExpr, valuesBlock);
            orphans = mapTreeNodes(projectId, hydrationQuery, null);
        }
        long totalDuration = System.currentTimeMillis() - startTime;
        log.info("[PERF] Top-level classes phase 2 (orphans): {} new results, total {}ms", orphans.size(), totalDuration);

        List<OntologyDto.TreeNode> merged = new java.util.ArrayList<>(phase1);
        merged.addAll(orphans);
        merged.sort(java.util.Comparator.comparing(n ->
                n.getLabel() != null ? n.getLabel().toLowerCase() : n.getId()));
        List<OntologyDto.TreeNode> result = merged.size() > limit ? merged.subList(0, limit) : merged;
        enrichWithEquivalentClasses(projectId, result);

        final List<OntologyDto.TreeNode> toStore = new java.util.ArrayList<>(result);
        final int finalLimit = limit;
        if (!toStore.isEmpty() && !hasDraft) {
            CompletableFuture.runAsync(() -> topLevelCacheService.put(projectId, toStore, finalLimit));
        }

        return result;
    }

    @Cacheable(value = "allClasses", key = "#projectId + '_' + #limit + '_' + T(self.research.ontology.owlEditor.service.SparqlQueryContext).cacheKeyComponent()")
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
              # Exclude built-in vocabulary/datatype IRIs (e.g. xsd:decimal) that only appear
              # here because they're a legitimate restriction filler (owl:onClass/someValuesFrom
              # on a data property) or a property's declared range — not a real ontology class.
              FILTER(!STRSTARTS(STR(?c), "http://www.w3.org/2001/XMLSchema#"))
              FILTER(!STRSTARTS(STR(?c), "http://www.w3.org/2000/01/rdf-schema#"))
              FILTER(!STRSTARTS(STR(?c), "http://www.w3.org/1999/02/22-rdf-syntax-ns#"))
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

        attachClassExpressions(projectId, nodeMap);

        List<OntologyDto.TreeNode> result = new ArrayList<>(nodeMap.values());

        enrichWithEquivalentClasses(projectId, result);
        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ [PERF] Loaded {} total classes in {}ms for project {}", result.size(), duration, projectId);

        return result;
    }

    private void attachClassExpressions(String projectId, Map<String, OntologyDto.TreeNode> nodeMap) {
        if (nodeMap.isEmpty()) return;

        if (datasetService.getGraphTripleCount(projectId) >= 500_000L) {
            log.info("⏭️ [PERF] attachClassExpressions skipped for large graph (>=500k triples), project {}", projectId);
            return;
        }
        long start = System.currentTimeMillis();

        String query = PREFIXES + """
            SELECT ?c ?rel ?bnode ?member ?memberLabel ?exprType WHERE {
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
                FILTER(isIRI(?member))
              }
              FILTER(isBlank(?bnode))
              {
                ?c rdfs:subClassOf ?bnode . BIND("subClassOf" AS ?rel)
              } UNION {
                ?c owl:equivalentClass ?bnode . BIND("equivalentClass" AS ?rel)
              }
              FILTER(isIRI(?c))
              OPTIONAL { ?member rdfs:label ?memberLabel }
            }
            LIMIT 5000
            """;

        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);

            Map<String, OntologyDto.ClassExpressionDto> byKey = new LinkedHashMap<>();
            Map<String, List<String>> operandLabelsByKey = new LinkedHashMap<>();

            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String classIri = resource(sol, "c");
                String memberIri = resource(sol, "member");
                String exprType = literal(sol, "exprType");
                String rel = literal(sol, "rel");
                Value bnodeVal = sol.getValue("bnode");
                if (classIri == null || memberIri == null || exprType.isBlank() || bnodeVal == null) continue;

                OntologyDto.TreeNode owner = nodeMap.get(classIri);
                if (owner == null) continue;

                String key = classIri + " " + bnodeVal.stringValue();
                OntologyDto.ClassExpressionDto expr = byKey.get(key);
                if (expr == null) {
                    expr = new OntologyDto.ClassExpressionDto();
                    expr.setId(classIri + "#expr-" + exprType + "-" + byKey.size());
                    expr.setExpressionType(exprType);
                    expr.setAxiomType(rel);
                    expr.setOperands(new ArrayList<>());
                    byKey.put(key, expr);
                    operandLabelsByKey.put(key, new ArrayList<>());

                    List<OntologyDto.ClassExpressionDto> list = owner.getClassExpressions();
                    if (list == null) {
                        list = new ArrayList<>();
                        owner.setClassExpressions(list);
                    }
                    list.add(expr);
                }

                boolean seen = false;
                for (Map<String, String> op : expr.getOperands()) {
                    if (memberIri.equals(op.get("iri"))) { seen = true; break; }
                }
                if (!seen) {
                    String memberLabel = literal(sol, "memberLabel");
                    if (memberLabel.isBlank()) memberLabel = localName(memberIri);
                    Map<String, String> operand = new LinkedHashMap<>();
                    operand.put("iri", memberIri);
                    operand.put("label", memberLabel);
                    expr.getOperands().add(operand);
                    operandLabelsByKey.get(key).add(memberLabel);
                }
            }

            for (Map.Entry<String, OntologyDto.ClassExpressionDto> entry : byKey.entrySet()) {
                OntologyDto.ClassExpressionDto expr = entry.getValue();
                List<String> labels = operandLabelsByKey.get(entry.getKey());
                switch (expr.getExpressionType()) {
                    case "union" -> expr.setDefinition(String.join(" or ", labels));
                    case "intersection" -> expr.setDefinition(String.join(" and ", labels));
                    case "complement" -> expr.setDefinition("not " + (labels.isEmpty() ? "?" : labels.get(0)));
                    case "oneOf" -> expr.setDefinition("{" + String.join(", ", labels) + "}");
                    default -> expr.setDefinition(String.join(", ", labels));
                }
            }
            log.info("⏱️ [PERF] attachClassExpressions: {} expressions in {}ms for project {}",
                    byKey.size(), System.currentTimeMillis() - start, projectId);
        } catch (Exception e) {

            log.warn("Could not attach class expressions for project {} after {}ms: {}",
                    projectId, System.currentTimeMillis() - start, e.getMessage());
        }
    }

    @Cacheable(value = "classChildren", key = "#projectId + '_' + #parentIri + '_' + #limit + '_' + #offset + '_' + T(self.research.ontology.owlEditor.service.SparqlQueryContext).cacheKeyComponent()")
    public List<OntologyDto.TreeNode> children(String projectId, String parentIri, int limit, int offset) {
        safeIri(parentIri);
        long startTime = System.currentTimeMillis();

        boolean largeGraph = datasetService.getGraphTripleCount(projectId) >= 500_000L;

        if (largeGraph) {

            String fetchQuery = PREFIXES + """
                SELECT DISTINCT ?child WHERE {
                  ?child rdfs:subClassOf <%s> .
                  FILTER(isIRI(?child) && ?child != <%s>)
                  %s
                  FILTER NOT EXISTS { ?child owl:deprecated true }
                }
                LIMIT %d OFFSET %d
                """.formatted(parentIri, parentIri,
                        draftEntityHiddenFilter(projectId, "?child"),
                        Math.max(1, limit), Math.max(0, offset));

            List<String> childIris = new ArrayList<>();
            TupleQueryResult fetchRs = datasetService.execSelect(projectId, fetchQuery);
            while (fetchRs.hasNext()) {
                Value v = fetchRs.next().getValue("child");
                if (v != null) childIris.add(v.stringValue());
            }
            long fetchMs = System.currentTimeMillis() - startTime;
            log.info("[PERF] children phase1 (large-graph) count={} in {}ms project={} parent={}",
                    childIris.size(), fetchMs, projectId, parentIri);

            if (childIris.isEmpty()) {
                return java.util.Collections.emptyList();
            }

            String valuesBlock = childIris.stream()
                    .map(iri -> "<" + iri + ">")
                    .collect(java.util.stream.Collectors.joining(" "));

            String hydrateQuery = PREFIXES + """
                SELECT ?child ?label ?description
                (EXISTS {
                  ?gc rdfs:subClassOf ?child .
                  FILTER(isIRI(?gc) && ?gc != ?child)
                  FILTER NOT EXISTS { ?gc owl:deprecated true }
                } AS ?hasChildren)
                WHERE {
                  VALUES ?child { %s }
                  OPTIONAL { ?child rdfs:label ?label }
                  OPTIONAL { ?child rdfs:comment ?description }
                }
                """.formatted(valuesBlock);

            List<OntologyDto.TreeNode> result = mapTreeNodes(projectId, hydrateQuery, parentIri);
            result.sort(Comparator.comparing(n -> n.getLabel() == null ? "" : n.getLabel().toLowerCase(java.util.Locale.ROOT)));
            enrichWithEquivalentClasses(projectId, result);

            long duration = System.currentTimeMillis() - startTime;
            log.info("[PERF] children {} count={} time={}ms project={}", parentIri, result.size(), duration, projectId);
            return result;
        }

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
                    parentIri, parentIri, Math.max(1, limit), Math.max(0, offset));

        List<OntologyDto.TreeNode> result = mapTreeNodes(projectId, query, parentIri);
        result.sort(Comparator.comparing(n -> n.getLabel() == null ? "" : n.getLabel().toLowerCase(java.util.Locale.ROOT)));
        enrichWithEquivalentClasses(projectId, result);

        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] children {} count={} time={}ms project={}", parentIri, result.size(), duration, projectId);
        return result;
    }

    @Cacheable(value = "ontologyProperties", key = "#projectId + '_' + #type + '_' + #limit + '_' + #offset + '_' + T(self.research.ontology.owlEditor.service.SparqlQueryContext).cacheKeyComponent()")
    public List<PropertyDto> properties(String projectId, String type, int limit, int offset) {
        long startTime = System.currentTimeMillis();
        String filter = switch (normalize(type)) {
            case "object" -> "FILTER(?kind = owl:ObjectProperty)";
            case "data" -> "FILTER(?kind = owl:DatatypeProperty)";
            default -> "";
        };

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
        enrichProperties(projectId, results);
        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ [PERF] Loaded {} properties in {}ms for project {}", results.size(), duration, projectId);
        return results;
    }

    private void enrichProperties(String projectId, List<PropertyDto> props) {
        if (props.isEmpty()) return;

        String values = props.stream()
                .map(p -> "(<" + p.getId() + ">)")
                .collect(java.util.stream.Collectors.joining(" "));

        String q = PREFIXES + """
            SELECT ?prop
                   (GROUP_CONCAT(DISTINCT STR(?domain); SEPARATOR="|") AS ?domains)
                   (GROUP_CONCAT(DISTINCT STR(?range); SEPARATOR="|") AS ?ranges)
                   (GROUP_CONCAT(DISTINCT STR(?inverse); SEPARATOR="|") AS ?inverseProperties)
                   (GROUP_CONCAT(DISTINCT STR(?disjoint); SEPARATOR="|") AS ?disjointProperties)
                   (GROUP_CONCAT(DISTINCT STR(?equiv); SEPARATOR="|") AS ?equivalentProperties)
                   (GROUP_CONCAT(DISTINCT STR(?char); SEPARATOR="|") AS ?characteristics)
            WHERE {
              VALUES (?prop) { %s }
              OPTIONAL { ?prop rdfs:domain ?domain . FILTER(isIRI(?domain)) }
              OPTIONAL { ?prop rdfs:range ?range . FILTER(isIRI(?range)) }
              OPTIONAL { { ?prop owl:inverseOf ?inverse } UNION { ?inverse owl:inverseOf ?prop } FILTER(isIRI(?inverse)) }
              OPTIONAL { { ?prop owl:propertyDisjointWith ?disjoint } UNION { ?disjoint owl:propertyDisjointWith ?prop } FILTER(isIRI(?disjoint) && ?disjoint != ?prop) }
              OPTIONAL { { ?prop owl:equivalentProperty ?equiv } UNION { ?equiv owl:equivalentProperty ?prop } FILTER(isIRI(?equiv) && ?equiv != ?prop) }
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
            GROUP BY ?prop
            """.formatted(values);

        TupleQueryResult rs = datasetService.execSelect(projectId, q);
        Map<String, PropertyDto> byIri = props.stream()
                .collect(java.util.stream.Collectors.toMap(PropertyDto::getId, p -> p, (a, b) -> a));
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String iri = resource(sol, "prop");
            if (iri == null) continue;
            PropertyDto dto = byIri.get(iri);
            if (dto == null) continue;
            dto.setDomains(splitPipe(literal(sol, "domains")));
            dto.setRanges(splitPipe(literal(sol, "ranges")));
            dto.setInverseProperties(splitPipe(literal(sol, "inverseProperties")));
            dto.setDisjointProperties(splitPipe(literal(sol, "disjointProperties")));
            dto.setEquivalentProperties(splitPipe(literal(sol, "equivalentProperties")));
            List<String> chars = splitPipe(literal(sol, "characteristics"));
            if (chars != null && !chars.isEmpty()) {
                dto.setCharacteristics(chars.stream()
                    .map(charIri -> localName(charIri).replace("Property", ""))
                    .toList());
            }
        }

        enrichPropertyChains(projectId, byIri);
    }

    private void enrichPropertyChains(String projectId, Map<String, PropertyDto> byIri) {
        if (byIri.isEmpty()) return;

        String values = byIri.keySet().stream()
                .map(iri -> "(<" + iri + ">)")
                .collect(java.util.stream.Collectors.joining(" "));

        String q = PREFIXES + """
            SELECT ?prop ?chainHead ?p0 ?p1 ?p2 ?p3 ?p4 WHERE {
              VALUES (?prop) { %s }
              ?prop owl:propertyChainAxiom ?chainHead .
              ?chainHead rdf:first ?p0 .
              OPTIONAL { ?chainHead rdf:rest ?n1 . ?n1 rdf:first ?p1 .
                OPTIONAL { ?n1 rdf:rest ?n2 . ?n2 rdf:first ?p2 .
                  OPTIONAL { ?n2 rdf:rest ?n3 . ?n3 rdf:first ?p3 .
                    OPTIONAL { ?n3 rdf:rest ?n4 . ?n4 rdf:first ?p4 . }
                  }
                }
              }
            }
            """.formatted(values);

        TupleQueryResult rs = datasetService.execSelect(projectId, q);
        Map<String, List<String>> byProp = new java.util.LinkedHashMap<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String prop = resource(sol, "prop");
            if (prop == null) continue;
            List<String> parts = new ArrayList<>();
            for (String var : java.util.List.of("p0", "p1", "p2", "p3", "p4")) {
                String val = resource(sol, var);
                if (val != null && !val.isBlank()) parts.add(val);
                else break;
            }
            if (parts.size() >= 2) {
                byProp.computeIfAbsent(prop, k -> new ArrayList<>()).add(String.join(" o ", parts));
            }
        }
        byProp.forEach((prop, chains) -> {
            PropertyDto dto = byIri.get(prop);
            if (dto != null) dto.setPropertyChains(chains);
        });
    }

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
              OPTIONAL { { ?prop owl:propertyDisjointWith ?disjoint } UNION { ?disjoint owl:propertyDisjointWith ?prop } FILTER(isIRI(?disjoint) && ?disjoint != ?prop) }
              OPTIONAL { { ?prop owl:equivalentProperty ?equiv } UNION { ?equiv owl:equivalentProperty ?prop } FILTER(isIRI(?equiv) && ?equiv != ?prop) }
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

            String annQuery = PREFIXES + """
                SELECT DISTINCT ?prop ?value WHERE {
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

    @Cacheable(value = "ontologyIndividuals", key = "#projectId + '_' + #limit + '_' + #offset + '_' + T(self.research.ontology.owlEditor.service.SparqlQueryContext).cacheKeyComponent()")
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

    @Cacheable(value = "individualCount", key = "#projectId + '_' + T(self.research.ontology.owlEditor.service.SparqlQueryContext).cacheKeyComponent()")
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

    @Cacheable(value = "ontologyAnnotationProperties", key = "#projectId + '_' + #limit + '_' + #offset + '_' + T(self.research.ontology.owlEditor.service.SparqlQueryContext).cacheKeyComponent()")
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

        datatypes.add("http://www.w3.org/2002/07/owl#rational");
        datatypes.add("http://www.w3.org/2002/07/owl#real");

        datatypes.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#langString");
        datatypes.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#PlainLiteral");
        datatypes.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral");

        datatypes.add("http://www.w3.org/2000/01/rdf-schema#Literal");

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

        Map<String, OntologyDto.TreeNode> seen = new java.util.LinkedHashMap<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String iri = resource(sol, parentIri == null ? "c" : "child");
            if (iri == null) {
                continue;
            }

            if (seen.containsKey(iri)) {

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

    private void enrichWithEquivalentClasses(String projectId, List<OntologyDto.TreeNode> nodes) {
        enrichWithEquivalentClassAxiom(projectId, nodes);
        enrichWithDisjointClasses(projectId, nodes);
        enrichWithRestrictions(projectId, nodes);
    }

    private void enrichWithEquivalentClassAxiom(String projectId, List<OntologyDto.TreeNode> nodes) {
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

        Map<String, OntologyDto.TreeNode> nodeMap = nodes.stream()
                .collect(java.util.stream.Collectors.toMap(OntologyDto.TreeNode::getId, n -> n, (a, b) -> a));
        byClass.forEach((cls, equivList) -> {
            OntologyDto.TreeNode node = nodeMap.get(cls);
            if (node != null) node.setEquivalentClasses(equivList);
        });
    }

    private void enrichWithDisjointClasses(String projectId, List<OntologyDto.TreeNode> nodes) {
        if (nodes.isEmpty()) return;

        String values = nodes.stream()
                .map(n -> "(<" + n.getId() + ">)")
                .collect(java.util.stream.Collectors.joining(" "));

        String q = PREFIXES + """
            SELECT DISTINCT ?cls ?disjoint ?disjointLabel WHERE {
              VALUES (?cls) { %s }
              {
                ?cls owl:disjointWith ?disjoint .
              } UNION {
                ?disjoint owl:disjointWith ?cls .
              }
              FILTER(?disjoint != ?cls && isIRI(?disjoint))
              OPTIONAL { ?disjoint rdfs:label ?disjointLabel }
            }
            """.formatted(values);

        TupleQueryResult rs = datasetService.execSelect(projectId, q);
        Map<String, List<Map<String, String>>> byClass = new java.util.LinkedHashMap<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String cls = resource(sol, "cls");
            String disjoint = resource(sol, "disjoint");
            if (cls == null || disjoint == null) continue;
            String disjointLabel = literal(sol, "disjointLabel");
            if (disjointLabel.isBlank()) disjointLabel = localName(disjoint);
            Map<String, String> entry = new java.util.LinkedHashMap<>();
            entry.put("iri", disjoint);
            entry.put("label", disjointLabel);
            byClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(entry);
        }

        Map<String, OntologyDto.TreeNode> nodeMap = nodes.stream()
                .collect(java.util.stream.Collectors.toMap(OntologyDto.TreeNode::getId, n -> n, (a, b) -> a));
        byClass.forEach((cls, disjointList) -> {
            OntologyDto.TreeNode node = nodeMap.get(cls);
            if (node != null) node.setDisjointWith(disjointList);
        });
    }

    private void enrichWithRestrictions(String projectId, List<OntologyDto.TreeNode> nodes) {
        if (nodes.isEmpty()) return;

        String values = nodes.stream()
                .map(n -> "(<" + n.getId() + ">)")
                .collect(java.util.stream.Collectors.joining(" "));
        String link = "?owner ?axProp ?restriction .\n?restriction owl:onProperty ?prop .";
        String preamble = """
            VALUES (?owner) { %s }
            VALUES (?axProp ?rel) { (rdfs:subClassOf "sub") (owl:equivalentClass "equiv") }
            """.formatted(values);
        String q = buildRestrictionSparqlBody("SELECT DISTINCT", "?owner ?rel", preamble, link);

        TupleQueryResult rs = datasetService.execSelect(projectId, q);
        Map<String, List<Map<String, String>>> byClass = new java.util.LinkedHashMap<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        while (rs.hasNext()) {
            BindingSet sol = rs.next();
            String owner = resource(sol, "owner");
            String restrictionNode = resourceOrBlank(sol, "restriction");
            if (owner == null || restrictionNode == null) continue;
            String rel = sol.hasBinding("rel") ? literal(sol, "rel") : "sub";
            String dedupeKey = owner + "|" + rel + "|" + restrictionNode;
            if (!seen.add(dedupeKey)) continue;

            String propIri = resource(sol, "prop");
            String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : localName(propIri);
            String restrictionType = sol.hasBinding("restrictionType") ? literal(sol, "restrictionType") : "some";
            String fillerIri = sol.hasBinding("filler") ? sol.getValue("filler").stringValue() : "";
            String fillerLabel = sol.hasBinding("fillerLabel") ? literal(sol, "fillerLabel") : localName(fillerIri);
            String cardinality = sol.hasBinding("card") ? literal(sol, "card") : "";

            Map<String, String> entry = new java.util.LinkedHashMap<>();
            entry.put("propertyIri", propIri);
            entry.put("propertyLabel", propLabel);
            entry.put("restrictionType", restrictionType);
            entry.put("fillerIri", fillerIri);
            entry.put("fillerLabel", fillerLabel);
            if (!cardinality.isEmpty()) entry.put("cardinality", cardinality);
            entry.put("axiomType", "equiv".equals(rel) ? "equivalentClass" : "subClassOf");
            byClass.computeIfAbsent(owner, k -> new ArrayList<>()).add(entry);
        }

        Map<String, OntologyDto.TreeNode> nodeMap = nodes.stream()
                .collect(java.util.stream.Collectors.toMap(OntologyDto.TreeNode::getId, n -> n, (a, b) -> a));
        byClass.forEach((cls, restrictionList) -> {
            OntologyDto.TreeNode node = nodeMap.get(cls);
            if (node != null) node.setRestrictions(restrictionList);
        });
    }

    private String buildClassRestrictionSparql(String classIri, String axiomProperty) {
        return buildClassRestrictionSparql(classIri, axiomProperty, false);
    }

    private String buildClassRestrictionSparql(String classIri, String axiomProperty, boolean distinct) {
        String link = "<%s> %s ?restriction .\n?restriction owl:onProperty ?prop .".formatted(classIri, axiomProperty);
        return buildRestrictionSparqlBody(distinct ? "SELECT DISTINCT" : "SELECT", "", "", link);
    }

    private String buildClassRestrictionSparqlBothAxes(String classIri) {
        String link = "<%s> ?axProp ?restriction .\n?restriction owl:onProperty ?prop .".formatted(classIri);
        String values = "VALUES (?axProp ?rel) { (rdfs:subClassOf \"sub\") (owl:equivalentClass \"equiv\") }";
        return buildRestrictionSparqlBody("SELECT DISTINCT", "?rel", values, link);
    }

    private String buildRestrictionSparqlBody(String selectKw, String extraVar, String preamble, String link) {
        return PREFIXES + """
            %s %s ?restriction ?prop ?propLabel ?restrictionType ?filler ?fillerLabel ?card WHERE {
              %s
              {
                %s
                ?restriction owl:someValuesFrom ?filler .
                BIND("some" AS ?restrictionType)
              } UNION {
                %s
                ?restriction owl:allValuesFrom ?filler .
                BIND("only" AS ?restrictionType)
              } UNION {
                %s
                ?restriction owl:hasValue ?filler .
                BIND("value" AS ?restrictionType)
              } UNION {
                %s
                ?restriction owl:hasSelf true .
                BIND("Self" AS ?filler) .
                BIND("some" AS ?restrictionType)
              } UNION {
                %s
                ?restriction owl:minQualifiedCardinality ?card .
                BIND("min" AS ?restrictionType)
                OPTIONAL { ?restriction owl:onClass ?filler }
                OPTIONAL { ?restriction owl:onDataRange ?filler }
              } UNION {
                %s
                ?restriction owl:maxQualifiedCardinality ?card .
                BIND("max" AS ?restrictionType)
                OPTIONAL { ?restriction owl:onClass ?filler }
                OPTIONAL { ?restriction owl:onDataRange ?filler }
              } UNION {
                %s
                ?restriction owl:qualifiedCardinality ?card .
                BIND("exactly" AS ?restrictionType)
                OPTIONAL { ?restriction owl:onClass ?filler }
                OPTIONAL { ?restriction owl:onDataRange ?filler }
              } UNION {
                %s
                ?restriction owl:minCardinality ?card .
                FILTER NOT EXISTS { ?restriction owl:minQualifiedCardinality ?any }
                BIND("min" AS ?restrictionType)
                BIND(owl:Thing AS ?filler)
              } UNION {
                %s
                ?restriction owl:maxCardinality ?card .
                FILTER NOT EXISTS { ?restriction owl:maxQualifiedCardinality ?any }
                BIND("max" AS ?restrictionType)
                BIND(owl:Thing AS ?filler)
              } UNION {
                %s
                ?restriction owl:cardinality ?card .
                FILTER NOT EXISTS { ?restriction owl:qualifiedCardinality ?any }
                BIND("exactly" AS ?restrictionType)
                BIND(owl:Thing AS ?filler)
              }
              OPTIONAL { ?prop rdfs:label ?propLabel }
              OPTIONAL { ?filler rdfs:label ?fillerLabel }
            }
            """.formatted(selectKw, extraVar, preamble, link, link, link, link, link, link, link, link, link, link);
    }

    private String normalizeBlankNodeId(String blankNodeId) {
        if (blankNodeId == null) return "";
        return blankNodeId.startsWith("_:") ? blankNodeId.substring(2) : blankNodeId;
    }

    private boolean blankNodeBindingMatches(BindingSet sol, String var, String normalizedId) {
        if (normalizedId == null || normalizedId.isBlank() || !sol.hasBinding(var)) {
            return false;
        }
        Value node = sol.getValue(var);
        if (node == null || !node.isBNode()) {
            return false;
        }
        String id = node.stringValue();
        return normalizedId.equals(id);
    }

    private String formatRestrictionManchester(BindingSet sol) {
        String propIri = resource(sol, "prop");
        String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : formatIriWithPrefix(propIri);
        String restrictionType = sol.hasBinding("restrictionType") ? literal(sol, "restrictionType") : "some";
        String fillerIri = sol.hasBinding("filler") ? sol.getValue("filler").stringValue() : "";
        String fillerLabel = sol.hasBinding("fillerLabel") ? literal(sol, "fillerLabel") : formatIriWithPrefix(fillerIri);
        String cardinality = sol.hasBinding("card") ? literal(sol, "card") : "";
        if ("Self".equals(fillerLabel)) {
            return propLabel + " some Self";
        }
        if (!cardinality.isEmpty()) {
            return propLabel + " " + restrictionType + " " + cardinality + " " + fillerLabel;
        }
        return propLabel + " " + restrictionType + " " + fillerLabel;
    }

    private String describeBlankNodeManchester(String projectId, String classIri, String blankNodeId) {
        if (blankNodeId == null || blankNodeId.isBlank()) return null;
        if (blankNodeId.startsWith("http://") || blankNodeId.startsWith("https://") || blankNodeId.startsWith("urn:")) {
            return null;
        }
        String normId = normalizeBlankNodeId(blankNodeId);

        if (classIri != null && !classIri.isBlank()) {
            for (String axiomProperty : List.of("owl:equivalentClass", "rdfs:subClassOf")) {
                try {
                    TupleQueryResult rs = datasetService.execSelect(projectId, buildClassRestrictionSparql(classIri, axiomProperty));
                    while (rs.hasNext()) {
                        BindingSet sol = rs.next();
                        if (blankNodeBindingMatches(sol, "restriction", normId)) {
                            return formatRestrictionManchester(sol);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not describe restriction for {} via {}: {}", blankNodeId, axiomProperty, e.getMessage());
                }
            }

            List<String> intersection = describeBlankNodeMemberListForClass(projectId, classIri, normId, "intersectionOf");
            if (!intersection.isEmpty()) return String.join(" and ", intersection);

            List<String> union = describeBlankNodeMemberListForClass(projectId, classIri, normId, "unionOf");
            if (!union.isEmpty()) return String.join(" or ", union);

            String complement = describeBlankNodeComplementForClass(projectId, classIri, normId);
            if (complement != null && !complement.isBlank()) return "not " + complement;

            List<String> oneOf = describeBlankNodeMemberListForClass(projectId, classIri, normId, "oneOf");
            if (!oneOf.isEmpty()) return "{" + String.join(", ", oneOf) + "}";
        }

        return null;
    }

    private List<String> describeBlankNodeMemberListForClass(String projectId, String classIri,
                                                             String normalizedBlankId, String listPred) {
        String owlPred = "intersectionOf".equals(listPred) ? "owl:intersectionOf"
                : "unionOf".equals(listPred) ? "owl:unionOf" : "owl:oneOf";
        String query = PREFIXES + """
            SELECT ?bnode ?member ?memberLabel WHERE {
              <%s> (owl:equivalentClass|rdfs:subClassOf) ?bnode .
              FILTER(isBlank(?bnode))
              ?bnode %s ?list .
              ?list rdf:rest*/rdf:first ?member .
              OPTIONAL { ?member rdfs:label ?memberLabel }
            }
            """.formatted(classIri, owlPred);
        List<String> labels = new ArrayList<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (!blankNodeBindingMatches(sol, "bnode", normalizedBlankId)) continue;
                String member = resourceOrBlank(sol, "member");
                if (member == null) continue;
                String label = sol.hasBinding("memberLabel") ? literal(sol, "memberLabel") : localName(member);
                labels.add(label.isBlank() ? member : label);
            }
        } catch (Exception e) {
            log.debug("Could not describe {} for {}: {}", listPred, normalizedBlankId, e.getMessage());
        }
        return labels;
    }

    private String describeBlankNodeComplementForClass(String projectId, String classIri, String normalizedBlankId) {
        String query = PREFIXES + """
            SELECT ?bnode ?complement ?complementLabel WHERE {
              <%s> (owl:equivalentClass|rdfs:subClassOf) ?bnode .
              FILTER(isBlank(?bnode))
              ?bnode owl:complementOf ?complement .
              OPTIONAL { ?complement rdfs:label ?complementLabel }
            }
            LIMIT 50
            """.formatted(classIri);
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (!blankNodeBindingMatches(sol, "bnode", normalizedBlankId)) continue;
                String complement = resourceOrBlank(sol, "complement");
                if (complement == null) return null;
                String label = sol.hasBinding("complementLabel") ? literal(sol, "complementLabel") : localName(complement);
                return label.isBlank() ? complement : label;
            }
        } catch (Exception e) {
            log.debug("Could not describe complement for {}: {}", normalizedBlankId, e.getMessage());
        }
        return null;
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

    private String formatIriWithPrefix(String iri) {
        if (iri == null || iri.isBlank()) {
            return "";
        }

        if (iri.startsWith("http://www.w3.org/2002/07/owl#")) {
            return "owl:" + iri.substring("http://www.w3.org/2002/07/owl#".length());
        }

        if (iri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")) {
            return "rdf:" + iri.substring("http://www.w3.org/1999/02/22-rdf-syntax-ns#".length());
        }

        if (iri.startsWith("http://www.w3.org/2000/01/rdf-schema#")) {
            return "rdfs:" + iri.substring("http://www.w3.org/2000/01/rdf-schema#".length());
        }

        if (iri.startsWith("http://www.w3.org/2001/XMLSchema#")) {
            return "xsd:" + iri.substring("http://www.w3.org/2001/XMLSchema#".length());
        }

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

    @Cacheable(value = "debugInfo", key = "#projectId + '_' + T(self.research.ontology.owlEditor.service.SparqlQueryContext).cacheKeyComponent()")
    public Map<String, Object> debugInfo(String projectId) {
        long startTime = System.currentTimeMillis();

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

        String restrictionQuery = PREFIXES + """
            SELECT DISTINCT ?ownerClass ?ownerLabel ?onProp ?propLabel ?restrictionType WHERE {
              {
                ?restriction owl:someValuesFrom <%s> ; owl:onProperty ?onProp .
                BIND("some" AS ?restrictionType)
              } UNION {
                ?restriction owl:allValuesFrom <%s> ; owl:onProperty ?onProp .
                BIND("all" AS ?restrictionType)
              } UNION {
                ?restriction owl:onClass <%s> ; owl:onProperty ?onProp .
                BIND("qualified" AS ?restrictionType)
              } UNION {
                ?restriction owl:hasValue <%s> ; owl:onProperty ?onProp .
                BIND("hasValue" AS ?restrictionType)
              }

              # Traverse from blank-node restriction to the named class that owns it
              {
                ?ownerClass rdfs:subClassOf ?restriction .
                FILTER(isIRI(?ownerClass))
              } UNION {
                ?ownerClass owl:equivalentClass ?restriction .
                FILTER(isIRI(?ownerClass))
              } UNION {
                # Restriction nested in an intersection/union expression
                ?container owl:intersectionOf|owl:unionOf ?list .
                ?list rdf:rest*/rdf:first ?restriction .
                { ?ownerClass rdfs:subClassOf ?container . FILTER(isIRI(?ownerClass)) }
                UNION
                { ?ownerClass owl:equivalentClass ?container . FILTER(isIRI(?ownerClass)) }
              }

              OPTIONAL { ?onProp rdfs:label ?propLabel }
              OPTIONAL { ?ownerClass rdfs:label ?ownerLabel }
            }
            LIMIT 500
            """.formatted(classIri, classIri, classIri, classIri);
        TupleQueryResult restrictions = datasetService.execSelect(projectId, restrictionQuery);
        while (restrictions.hasNext()) {
            BindingSet sol = restrictions.next();
            Map<String, String> usage = new LinkedHashMap<>();
            usage.put("type", "restriction");
            String ownerIri = resource(sol, "ownerClass");
            String onPropIri = resource(sol, "onProp");
            if (ownerIri != null && onPropIri != null) {
                String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : localName(onPropIri);
                String restrictionType = sol.hasBinding("restrictionType") ? literal(sol, "restrictionType") : "some";
                String ownerLabel = sol.hasBinding("ownerLabel") ? literal(sol, "ownerLabel") : localName(ownerIri);
                usage.put("subject", ownerIri);
                usage.put("subjectLabel", ownerLabel);
                usage.put("context", "SubClassOf " + propLabel + " " + restrictionType + " <this>");
                usages.add(usage);
            }
        }

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

                if (propIri.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")) {
                    continue;
                }

                usage.put("subject", classIri);
                String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : localName(propIri);
                String value = sol.hasBinding("value") ? sol.getValue("value").stringValue() : "";

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

    public Map<String, Object> classAnnotations(String projectId, String classIri) {
        safeIri(classIri);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", classIri);

        String annQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?value WHERE {
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

            result.put("label", localName(classIri));
            result.put("annotations", annotations);
            return result;
        }
        result.put("label", label != null ? label : localName(classIri));
        result.put("annotations", annotations);
        return result;
    }

    public Map<String, String> batchAnnotations(String projectId, List<String> iris, String propertyIri) {
        if (iris == null || iris.isEmpty()) return Collections.emptyMap();
        iris.forEach(OntologyQueryService::safeIri);
        safeIri(propertyIri);

        StringBuilder values = new StringBuilder();
        for (String iri : iris) values.append("<").append(iri).append("> ");

        String query = PREFIXES + """
            SELECT ?entity ?value WHERE {
              VALUES ?entity { %s }
              ?entity <%s> ?value .
              FILTER(isLiteral(?value))
            }
            """.formatted(values, propertyIri);

        Map<String, String> result = new LinkedHashMap<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String entityIri = resource(sol, "entity");
                if (entityIri != null && sol.hasBinding("value") && !result.containsKey(entityIri)) {
                    result.put(entityIri, sol.getValue("value").stringValue());
                }
            }
        } catch (Exception e) {
            log.warn("[batchAnnotations] SPARQL error for project {}: {}", projectId, e.getMessage());
        }
        return result;
    }

    public Map<String, Object> classDetails(String projectId, String classIri) {
        safeIri(classIri);
        long startTime = System.currentTimeMillis();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", classIri);
        final Executor queryPool = queryExecutorFor(projectId);
        if (datasetService.isKnownLargeProject(projectId)) {
            log.info("[PERF] classDetails using reduced SPARQL parallelism for large project {}", projectId);
        }

        CompletableFuture<TupleQueryResult> annFuture = queryAsync(SparqlQueryContext.wrap(() -> {
            String annQuery = PREFIXES + """
                SELECT DISTINCT ?prop ?value WHERE {
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
        }), queryPool);

        CompletableFuture<TupleQueryResult> namedAxiomsFuture = queryAsync(SparqlQueryContext.wrap(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?kind ?target ?label WHERE {
                  {
                    <%1$s> rdfs:subClassOf ?target .
                    BIND("sub" AS ?kind)
                    FILTER(isIRI(?target))
                    FILTER(?target != owl:Thing && ?target != owl:Nothing && ?target != <%1$s>)
                    FILTER(!STRSTARTS(STR(?target), "http://www.w3.org/2002/07/owl#"))
                    FILTER(!STRSTARTS(STR(?target), "http://www.w3.org/2000/01/rdf-schema#"))
                    FILTER(!STRSTARTS(STR(?target), "http://ontocode.org/restriction/"))
                  } UNION {
                    { <%1$s> owl:equivalentClass ?target . }
                    UNION
                    { ?target owl:equivalentClass <%1$s> . }
                    BIND("equiv" AS ?kind)
                    FILTER(isIRI(?target) && ?target != <%1$s>
                           && !STRSTARTS(STR(?target), "http://ontocode.org/restriction/"))
                  } UNION {
                    {
                      { <%1$s> owl:disjointWith ?target . }
                      UNION
                      { ?target owl:disjointWith <%1$s> . }
                      UNION
                      {
                        ?allDisjoint a owl:AllDisjointClasses ;
                                     owl:members ?list .
                        ?list rdf:rest*/rdf:first <%1$s> .
                        ?list rdf:rest*/rdf:first ?target .
                      }
                    }
                    BIND("disjoint" AS ?kind)
                    FILTER(isIRI(?target) && ?target != <%1$s>)
                  }
                  OPTIONAL { ?target rdfs:label ?label }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }), queryPool);

        CompletableFuture<TupleQueryResult> restrictionsFuture = queryAsync(SparqlQueryContext.wrap(() ->
            datasetService.execSelect(projectId, buildClassRestrictionSparqlBothAxes(classIri))),
            queryPool);

        CompletableFuture<TupleQueryResult> anonymousFuture = queryAsync(SparqlQueryContext.wrap(() -> {
            String q = PREFIXES + """
                SELECT ?rel ?bnode ?member ?memberLabel ?exprType WHERE {
                  {
                    <%1$s> rdfs:subClassOf ?bnode . BIND("sub" AS ?rel)
                  } UNION {
                    <%1$s> owl:equivalentClass ?bnode . BIND("equiv" AS ?rel)
                  }
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
                LIMIT 1000
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }), queryPool);

        CompletableFuture<TupleQueryResult> listAxiomsFuture = queryAsync(() -> {
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

        CompletableFuture<TupleQueryResult> inferredFuture = queryAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?kind ?target ?label WHERE {
                  {
                    GRAPH <http://www.ontotext.com/inferred> {
                      <%1$s> owl:equivalentClass ?target .
                    }
                    FILTER NOT EXISTS {
                      GRAPH <http://www.ontotext.com/explicit> {
                        <%1$s> owl:equivalentClass ?target .
                      }
                    }
                    BIND("equiv" AS ?kind)
                    FILTER(isIRI(?target) && ?target != <%1$s>)
                  } UNION {
                    GRAPH <http://www.ontotext.com/inferred> {
                      <%1$s> rdfs:subClassOf ?target .
                    }
                    FILTER NOT EXISTS {
                      GRAPH <http://www.ontotext.com/explicit> {
                        <%1$s> rdfs:subClassOf ?target .
                      }
                    }
                    BIND("super" AS ?kind)
                    FILTER(isIRI(?target) && ?target != owl:Thing && ?target != <%1$s>)
                  } UNION {
                    GRAPH <http://www.ontotext.com/inferred> {
                      { <%1$s> owl:disjointWith ?target . }
                      UNION
                      { ?target owl:disjointWith <%1$s> . }
                    }
                    FILTER NOT EXISTS {
                      GRAPH <http://www.ontotext.com/explicit> {
                        { <%1$s> owl:disjointWith ?target . }
                        UNION
                        { ?target owl:disjointWith <%1$s> . }
                      }
                    }
                    BIND("disjoint" AS ?kind)
                    FILTER(isIRI(?target) && ?target != <%1$s>)
                  }
                  OPTIONAL { ?target rdfs:label ?label }
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture<TupleQueryResult> gciFuture = queryAsync(() -> {
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

        CompletableFuture<TupleQueryResult> ancestorFuture = queryAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?ancestor ?super ?label
                       ?onProp ?propLabel ?restrictionType ?filler ?fillerLabel ?card
                WHERE {
                  <%1$s> rdfs:subClassOf+ ?ancestor .
                  { ?ancestor rdfs:subClassOf ?super . }
                  UNION
                  { ?ancestor owl:equivalentClass ?super . FILTER(isBlank(?super)) }
                  FILTER(isBlank(?super) || (?super != owl:Thing && ?super != <%1$s>))
                  OPTIONAL { ?super rdfs:label ?label }
                  OPTIONAL {
                    ?super owl:onProperty ?onProp .
                    OPTIONAL { ?onProp rdfs:label ?propLabel }
                    {
                      ?super owl:someValuesFrom ?filler . BIND("some" AS ?restrictionType)
                    } UNION {
                      ?super owl:allValuesFrom ?filler . BIND("only" AS ?restrictionType)
                    } UNION {
                      ?super owl:hasValue ?filler . BIND("value" AS ?restrictionType)
                    } UNION {
                      ?super owl:hasSelf true . BIND("Self" AS ?filler) BIND("some" AS ?restrictionType)
                    } UNION {
                      ?super owl:minQualifiedCardinality ?card . BIND("min" AS ?restrictionType)
                      OPTIONAL { ?super owl:onClass ?filler }
                      OPTIONAL { ?super owl:onDataRange ?filler }
                    } UNION {
                      ?super owl:maxQualifiedCardinality ?card . BIND("max" AS ?restrictionType)
                      OPTIONAL { ?super owl:onClass ?filler }
                      OPTIONAL { ?super owl:onDataRange ?filler }
                    } UNION {
                      ?super owl:qualifiedCardinality ?card . BIND("exactly" AS ?restrictionType)
                      OPTIONAL { ?super owl:onClass ?filler }
                      OPTIONAL { ?super owl:onDataRange ?filler }
                    }
                    OPTIONAL { ?filler rdfs:label ?fillerLabel }
                  }
                }
                LIMIT 500
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture.allOf(
            annFuture, namedAxiomsFuture, restrictionsFuture, anonymousFuture,
            listAxiomsFuture, inferredFuture, gciFuture, ancestorFuture
        ).join();

        long queryTime = System.currentTimeMillis() - startTime;
        log.info("[PERF] classDetails all parallel queries completed in {}ms for {}", queryTime, localName(classIri));

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

        List<Map<String, String>> subClassAxioms = new ArrayList<>();
        List<Map<String, String>> equivAxioms = new ArrayList<>();
        List<Map<String, String>> disjointAxioms = new ArrayList<>();
        TupleQueryResult namedRs = namedAxiomsFuture.join();
        while (namedRs.hasNext()) {
            BindingSet sol = namedRs.next();
            String targetIri = resource(sol, "target");
            String kind = sol.hasBinding("kind") ? literal(sol, "kind") : "";
            if (targetIri == null) continue;
            Map<String, String> axiom = new LinkedHashMap<>();
            axiom.put("id", targetIri);
            axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(targetIri));
            switch (kind) {
                case "sub" -> {
                    axiom.put("type", "SubClassOf");
                    subClassAxioms.add(axiom);
                }
                case "equiv" -> {
                    axiom.put("type", "EquivalentTo");
                    equivAxioms.add(axiom);
                }
                case "disjoint" -> {
                    axiom.put("type", "DisjointWith");
                    disjointAxioms.add(axiom);
                }
                default -> { }
            }
        }
        java.util.Comparator<Map<String, String>> byDefinition =
                java.util.Comparator.comparing(a -> a.getOrDefault("definition", ""), String.CASE_INSENSITIVE_ORDER);
        subClassAxioms.sort(byDefinition);
        disjointAxioms.sort(byDefinition);

        TupleQueryResult restrictionsRs = restrictionsFuture.join();
        Set<String> seenRestrictions = new LinkedHashSet<>();
        Set<String> seenEquivRestrictions = new LinkedHashSet<>();
        while (restrictionsRs.hasNext()) {
            BindingSet sol = restrictionsRs.next();
            String restrictionNode = resourceOrBlank(sol, "restriction");
            if (restrictionNode == null) continue;
            String rel = sol.hasBinding("rel") ? literal(sol, "rel") : "sub";
            boolean isEquiv = "equiv".equals(rel);
            Set<String> seen = isEquiv ? seenEquivRestrictions : seenRestrictions;
            if (seen.contains(restrictionNode)) continue;
            seen.add(restrictionNode);
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
            axiom.put("type", isEquiv ? "EquivalentTo" : "SubClassOf");
            axiom.put("definition", definition);
            axiom.put("isRestriction", "true");
            axiom.put("propertyIri", propIri);
            axiom.put("restrictionType", restrictionType);
            axiom.put("fillerIri", fillerIri);
            if (!cardinality.isEmpty()) {
                axiom.put("cardinality", cardinality);
            }
            (isEquiv ? equivAxioms : subClassAxioms).add(axiom);
        }

        TupleQueryResult anonRs = anonymousFuture.join();
        Map<String, List<String>> anonMemberLabels = new LinkedHashMap<>();
        Map<String, String> anonExprType = new LinkedHashMap<>();
        Map<String, String> anonRel = new LinkedHashMap<>();
        while (anonRs.hasNext()) {
            BindingSet sol = anonRs.next();
            String rel = sol.hasBinding("rel") ? literal(sol, "rel") : "sub";
            String bnode = sol.getValue("bnode").stringValue();
            String groupKey = rel + "|" + bnode;
            String memberIri = resource(sol, "member");
            String memberLabel = sol.hasBinding("memberLabel") ? literal(sol, "memberLabel") : localName(memberIri);
            String exprType = sol.hasBinding("exprType") ? literal(sol, "exprType") : "";
            if (memberIri != null) {
                anonMemberLabels.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(memberLabel);
                anonExprType.putIfAbsent(groupKey, exprType);
                anonRel.putIfAbsent(groupKey, rel);
            }
        }
        for (Map.Entry<String, List<String>> entry : anonMemberLabels.entrySet()) {
            String groupKey = entry.getKey();
            List<String> labels = entry.getValue();
            if (labels == null || labels.isEmpty()) continue;
            String exprType = anonExprType.getOrDefault(groupKey, "");
            boolean isEquiv = "equiv".equals(anonRel.get(groupKey));
            String definition = switch (exprType) {
                case "intersection" -> String.join(" and ", labels);
                case "union"        -> String.join(" or ", labels);
                case "complement"   -> "not " + labels.get(0);
                case "oneOf"        -> "{" + String.join(", ", labels) + "}";
                default             -> String.join(", ", labels);
            };
            Map<String, String> axiom = new LinkedHashMap<>();
            axiom.put("id", groupKey.substring(groupKey.indexOf('|') + 1));
            axiom.put("type", isEquiv ? "EquivalentTo" : "SubClassOf");
            axiom.put("definition", definition);
            axiom.put("isComplex", "true");
            axiom.put("expressionType", exprType);
            (isEquiv ? equivAxioms : subClassAxioms).add(axiom);
        }

        details.put("subClassOfAxioms", subClassAxioms);

        details.put("equivalentClassesAxioms", equivAxioms);
        details.put("disjointClassesAxioms", disjointAxioms);

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

        TupleQueryResult inferredRs = inferredFuture.join();
        List<Map<String, String>> inferredEquivAxioms = new ArrayList<>();
        List<Map<String, String>> inferredSubClassAxioms = new ArrayList<>();
        List<Map<String, String>> inferredDisjointAxioms = new ArrayList<>();
        while (inferredRs.hasNext()) {
            BindingSet sol = inferredRs.next();
            String targetIri = resource(sol, "target");
            String kind = sol.hasBinding("kind") ? literal(sol, "kind") : "";
            if (targetIri == null) continue;
            Map<String, String> axiom = new LinkedHashMap<>();
            axiom.put("id", targetIri);
            axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(targetIri));
            axiom.put("isInferred", "true");
            switch (kind) {
                case "equiv" -> {
                    axiom.put("type", "EquivalentTo");
                    inferredEquivAxioms.add(axiom);
                }
                case "super" -> {
                    axiom.put("type", "SubClassOf");
                    inferredSubClassAxioms.add(axiom);
                }
                case "disjoint" -> {
                    axiom.put("type", "DisjointWith");
                    inferredDisjointAxioms.add(axiom);
                }
                default -> { }
            }
        }
        details.put("inferredEquivalentClassesAxioms", inferredEquivAxioms);
        details.put("inferredSubClassOfAxioms", inferredSubClassAxioms);
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

        Map<String, String> knownBlankDefinitions = new LinkedHashMap<>();
        for (Map<String, String> ax : subClassAxioms) {
            if (ax.get("id") != null && ax.get("definition") != null) {
                knownBlankDefinitions.put(ax.get("id"), ax.get("definition"));
            }
        }
        for (Map<String, String> ax : equivAxioms) {
            if (ax.get("id") != null && ax.get("definition") != null) {
                knownBlankDefinitions.put(ax.get("id"), ax.get("definition"));
            }
        }
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

                    String onProp = resource(sol, "onProp");
                    if (onProp != null) {
                        String propLabel = sol.hasBinding("propLabel") ? literal(sol, "propLabel") : formatIriWithPrefix(onProp);
                        String restrictionType = sol.hasBinding("restrictionType") ? literal(sol, "restrictionType") : "some";
                        String fillerIri = sol.hasBinding("filler") ? sol.getValue("filler").stringValue() : "";
                        String fillerLabel = sol.hasBinding("fillerLabel") ? literal(sol, "fillerLabel") : formatIriWithPrefix(fillerIri);
                        String cardinality = sol.hasBinding("card") ? literal(sol, "card") : "";
                        axiom.put("isRestriction", "true");
                        axiom.put("propertyIri", onProp);
                        axiom.put("restrictionType", restrictionType);
                        axiom.put("fillerIri", fillerIri);
                        if (!cardinality.isEmpty()) {
                            axiom.put("cardinality", cardinality);
                        }
                        String definition = cardinality.isEmpty()
                                ? propLabel + " " + restrictionType + " " + fillerLabel
                                : propLabel + " " + restrictionType + " " + cardinality + " " + fillerLabel;
                        axiom.put("manchester", definition);
                        axiom.put("definition", definition);
                    } else {
                        String known = knownBlankDefinitions.get(superIri);
                        if (known != null && !known.isBlank()) {
                            axiom.put("manchester", known);
                            axiom.put("definition", known);
                        } else {
                            String manchester = describeBlankNodeManchester(projectId, classIri, superIri);
                            if (manchester != null && !manchester.isBlank()) {
                                axiom.put("manchester", manchester);
                                axiom.put("definition", manchester);
                            } else {
                                axiom.put("definition", "Anonymous superclass");
                            }
                        }
                    }
                } else {
                    axiom.put("definition", sol.hasBinding("label") ? literal(sol, "label") : localName(superIri));
                }
                anonymousAncestorAxioms.add(axiom);
            }
        }

        for (Map<String, String> subAxiom : subClassAxioms) {
            if (!"true".equals(subAxiom.get("isRestriction"))) continue;
            String restrictionId = subAxiom.get("id");
            if (restrictionId == null) continue;
            String ancestorKey = classIri + "|" + restrictionId;
            if (seenAncestors.contains(ancestorKey)) continue;
            seenAncestors.add(ancestorKey);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", restrictionId);
            entry.put("type", "SubClassOf");
            entry.put("ancestorIri", classIri);
            entry.put("navigable", "false");
            entry.put("definition", subAxiom.get("definition") != null ? subAxiom.get("definition") : "Anonymous restriction");

            entry.put("isRestriction", "true");
            if (subAxiom.get("propertyIri") != null) entry.put("propertyIri", subAxiom.get("propertyIri"));
            if (subAxiom.get("restrictionType") != null) entry.put("restrictionType", subAxiom.get("restrictionType"));
            if (subAxiom.get("fillerIri") != null) entry.put("fillerIri", subAxiom.get("fillerIri"));
            if (subAxiom.get("cardinality") != null) entry.put("cardinality", subAxiom.get("cardinality"));
            anonymousAncestorAxioms.add(entry);
        }
        details.put("anonymousAncestorAxioms", anonymousAncestorAxioms);

        long duration = System.currentTimeMillis() - startTime;
        log.info("[PERF] classDetails for {} completed in {}ms project={} (parallel)", localName(classIri), duration, projectId);
        return details;
    }

    public void enrichOwlApiClassDetails(String projectId, String classIri, Map<String, Object> details) {
        safeIri(classIri);
        if (details == null || details.isEmpty()) {
            return;
        }
        long startTime = System.currentTimeMillis();
        final Executor queryPool = queryExecutorFor(projectId);

        CompletableFuture<TupleQueryResult> annFuture = queryAsync(SparqlQueryContext.wrap(() -> {
            String annQuery = PREFIXES + """
                SELECT DISTINCT ?prop ?value WHERE {
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
        }), queryPool);

        CompletableFuture<TupleQueryResult> disjointFuture = queryAsync(() -> {
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

        CompletableFuture<TupleQueryResult> disjointUnionFuture = queryAsync(() -> {
            String q = PREFIXES + """
                SELECT ?list ?member WHERE {
                  <%s> owl:disjointUnionOf ?list .
                  ?list rdf:rest*/rdf:first ?member .
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture<TupleQueryResult> hasKeyFuture = queryAsync(() -> {
            String q = PREFIXES + """
                SELECT ?keyList ?prop WHERE {
                  <%s> owl:hasKey ?keyList .
                  ?keyList rdf:rest*/rdf:first ?prop .
                }
                """.formatted(classIri);
            return datasetService.execSelect(projectId, q);
        }, queryPool);

        CompletableFuture<TupleQueryResult> inferredEquivFuture = queryAsync(() -> {
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

        CompletableFuture<TupleQueryResult> inferredSuperFuture = queryAsync(() -> {
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

        CompletableFuture<TupleQueryResult> inferredDisjointFuture = queryAsync(() -> {
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

        CompletableFuture<TupleQueryResult> gciFuture = queryAsync(() -> {
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

        CompletableFuture<TupleQueryResult> ancestorFuture = queryAsync(() -> {
            String q = PREFIXES + """
                SELECT DISTINCT ?ancestor ?super ?label WHERE {
                  <%s> rdfs:subClassOf+ ?ancestor .
                  { ?ancestor rdfs:subClassOf ?super . }
                  UNION
                  { ?ancestor owl:equivalentClass ?super . FILTER(isBlank(?super)) }
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
                    String manchester = describeBlankNodeManchester(projectId, classIri, superIri);
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

    @Cacheable(value = "classInstances", key = "#projectId + '_' + #classIri + '_' + T(self.research.ontology.owlEditor.service.SparqlQueryContext).cacheKeyComponent()")
    public List<Map<String, Object>> getClassInstances(String projectId, String classIri) {
        safeIri(classIri);
        long startTime = System.currentTimeMillis();
        List<Map<String, Object>> instances = new ArrayList<>();
        Set<String> seenIndividuals = new LinkedHashSet<>();

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

    @Cacheable(value = "classInstanceCounts", key = "#projectId + '_' + T(self.research.ontology.owlEditor.service.SparqlQueryContext).cacheKeyComponent()")
    public Map<String, Map<String, Integer>> getClassInstanceCounts(String projectId) {
        long startTime = System.currentTimeMillis();
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();

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

    public Map<String, Object> getIndividualDetails(String projectId, String individualIri) {
        safeIri(individualIri);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", individualIri);

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

        String annQuery = PREFIXES + """
            SELECT DISTINCT ?prop ?value WHERE {
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

                continue;
            }

            propertyAssertions.add(assertion);
        }
        details.put("propertyAssertions", propertyAssertions);

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

