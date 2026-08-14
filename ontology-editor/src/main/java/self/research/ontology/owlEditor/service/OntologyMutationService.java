package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.BindingSet;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.controller.VisualizationController;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class OntologyMutationService {

    private static final Logger log = LoggerFactory.getLogger(OntologyMutationService.class);

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
        """;

    private final SparqlDatasetService datasetService;
    private final OntologyIndexService indexService;
    private final ProjectMetadataService metadataService;
    private final GraphGeneratingService graphGeneratingService;
    private final TopLevelClassCacheService topLevelCacheService;
    private final StorageManager storageManager;
    private final Executor metadataExecutor;

    @Autowired @Lazy
    private VisualizationController visualizationController;

    @Autowired(required = false) @Nullable
    private HierarchyIndexService hierarchyIndexService;

    @Autowired(required = false) @Nullable
    private ProjectOntologyCache ontologyCache;

    @Autowired(required = false) @Nullable
    private DesktopOwlApiMutationService desktopOwlApiMutationService;

    @Autowired(required = false) @Nullable
    private DraftCopyService draftCopyService;

    @Autowired(required = false) @Nullable
    private EntityUsageIndexService entityUsageIndexService;

    @Autowired(required = false) @Nullable
    private ClassDetailCacheService classDetailCacheService;

    @Autowired @Lazy
    private MainGraphRevisionService mainGraphRevisionService;

    @Autowired(required = false) @Nullable
    private EditorReasonerCacheService editorReasonerCacheService;

    // Fire-and-forget cross-service call to bust ontology-plugin-service's reasoner
    // cache after a save — short timeouts since this must never hold up a mutation
    // waiting on another service (see invalidateReasonerCaches()).
    @Value("${ontology.plugin-service.url:http://localhost:8087}")
    private String pluginServiceUrl;

    private final RestTemplate reasonerCacheInvalidationRestTemplate = buildShortTimeoutRestTemplate();

    private static RestTemplate buildShortTimeoutRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3_000);
        f.setReadTimeout(5_000);
        return new RestTemplate(f);
    }

    public OntologyMutationService(SparqlDatasetService datasetService,
                                   OntologyIndexService indexService,
                                   ProjectMetadataService metadataService,
                                   GraphGeneratingService graphGeneratingService,
                                   TopLevelClassCacheService topLevelCacheService,
                                   StorageManager storageManager,
                                   @Qualifier("metadataExecutor") Executor metadataExecutor) {
        this.datasetService = datasetService;
        this.indexService = indexService;
        this.metadataService = metadataService;
        this.graphGeneratingService = graphGeneratingService;
        this.topLevelCacheService = topLevelCacheService;
        this.storageManager = storageManager;
        this.metadataExecutor = metadataExecutor;
    }

    /**
     * Invalidates the Code View content cache after a mutation lands in the PUBLIC graph.
     * A stale cached snapshot would otherwise (a) hide the new/changed entity when Code View
     * is opened, and (b) if the user then edits and saves Code View, get treated as the
     * complete authoritative ontology — code-view-save does a full graph clear + reload, not
     * a merge, so anything real missing from that stale text is silently destroyed. Draft
     * mutations don't touch the public graph, so they must not evict this cache.
     */
    private void invalidatePublicCodeViewCache(String projectId, boolean draft) {
        if (!draft) {
            storageManager.clearCodeViewCache(projectId);
        }
    }

    /**
     * Bust every cached reasoner ontology for this project after a mutation —
     * draft and public alike, since ontology-plugin-service's cache is keyed by
     * plain projectId with no draft/public distinction, so a draft edit can
     * otherwise leave a stale cached object behind for whichever scope reasons
     * over that same key next. Without this, the reasoner can keep silently
     * reasoning over a stale, pre-edit ontology — reporting consistency/
     * classification results that no longer match what's actually saved.
     */
    private void invalidateReasonerCaches(String projectId) {
        if (editorReasonerCacheService != null) {
            try {
                editorReasonerCacheService.invalidateOntology(projectId);
            } catch (Exception e) {
                log.warn("[MUTATION] Failed invalidating editor reasoner cache for project {}", projectId, e);
            }
        }

        // Cross-service call, fully async — a slow/unreachable plugin-service must
        // never block or fail the save that triggered this.
        metadataExecutor.execute(() -> {
            try {
                String url = pluginServiceUrl + "/api/reasoner/clear-cache/"
                    + URLEncoder.encode(projectId, StandardCharsets.UTF_8);
                reasonerCacheInvalidationRestTemplate.postForEntity(url, null, Map.class);
            } catch (Exception e) {
                log.warn("[MUTATION] Failed busting plugin-service reasoner cache for project {} (non-fatal)",
                    projectId, e);
            }
        });
    }

    /**
     * Apply ontology mutations. Spring cache eviction is centralized in
     * {@link SparqlDatasetService#execUpdate} via {@link OntologySpringCacheEvictionService}.
     */
    public void apply(String projectId, List<MutationOp> ops) {
        apply(projectId, ops, false, null);
    }

    /**
     * Apply mutations to the user's draft named graph (private editing — not visible to other users).
     */
    public void applyDraft(String projectId, String userId, List<MutationOp> ops) {
        apply(projectId, ops, true, userId);
    }

    private void apply(String projectId, List<MutationOp> ops, boolean draft, String userId) {
        if (ops == null || ops.isEmpty()) {
            log.warn("[MUTATION] No operations to apply for project: {}", projectId);
            return;
        }

        log.info("[MUTATION] Applying {} mutations for project={}", ops.size(), projectId);
        long mutationStart = System.currentTimeMillis();
        
        String sparql = PREFIXES + "\n" + ops.stream()
                .map(op -> toUpdate(projectId, op))
                .filter(s -> s != null && !s.isBlank()) // Filter out empty statements
                .collect(Collectors.joining("\n;\n"));
        
        // Check if we have any actual statements after filtering
        if (sparql.trim().equals(PREFIXES.trim())) {
            String opTypes = ops.stream().map(MutationOp::type).collect(Collectors.joining(", "));
            log.error("[MUTATION] No valid SPARQL produced for ops: {}", opTypes);
            throw new IllegalArgumentException(
                    "Mutation could not be applied: missing required fields or unsupported restriction type (ops: "
                            + opTypes + ")");
        }
        
        log.info("[MUTATION] Generated SPARQL (BEFORE graph injection):");
        log.info("[MUTATION] {}", sparql);
        
        try {
            // desktop: OWLAPI patch or in-memory SPARQL; defer Fuseki until SPARQL/graph.
            if (!draft && desktopOwlApiMutationService != null
                    && desktopOwlApiMutationService.tryApply(projectId, ops, sparql)) {
                long version = metadataService.incrementMutationVersion(projectId);
                if (ontologyCache != null) {
                    ontologyCache.updateCachedVersion(projectId, version);
                }
                topLevelCacheService.evict(projectId);
                invalidatePublicCodeViewCache(projectId, draft);
                if (hierarchyIndexService != null) {
                    hierarchyIndexService.markStale(projectId);
                }
                if (entityUsageIndexService != null || classDetailCacheService != null) {
                    List<String> affectedIris = ops.stream()
                        .flatMap(op -> Stream.of(op.iri(), op.parent(), op.target(), op.classIri()))
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
                    if (entityUsageIndexService != null) entityUsageIndexService.invalidate(projectId, affectedIris);
                    if (classDetailCacheService != null) classDetailCacheService.invalidate(projectId, affectedIris);
                }
                graphGeneratingService.clearGraphCache();
                if (visualizationController != null) {
                    visualizationController.clearCache(projectId);
                }
                invalidateReasonerCaches(projectId);
                return;
            }

            MutationContext.setOps(ops);
            long sparqlStart = System.currentTimeMillis();
            if (draft) {
                requireDraftCopyReady(projectId, userId);
                datasetService.execDraftUpdateCopyOnSwitch(projectId, userId, sparql);
            } else {
                datasetService.execUpdate(projectId, sparql);
                if (mainGraphRevisionService != null) {
                    mainGraphRevisionService.incrementRevision(projectId);
                }
            }
            long sparqlDuration = System.currentTimeMillis() - sparqlStart;
            log.info("[MUTATION] SPARQL update completed in {}ms for project={}", sparqlDuration, projectId);

            // OWLAPI patch/evict + Spring cache eviction handled in execUpdate → mutationCoordinator
            // MongoDB L2 cache (topLevelCacheService) only evicted for public mutations;
            // draft mutations leave the public graph unchanged so L2 stays valid.
            if (!draft) {
                topLevelCacheService.evict(projectId);
                invalidatePublicCodeViewCache(projectId, draft);
            }
            if (hierarchyIndexService != null) {
                hierarchyIndexService.markStale(projectId);
            }
            if (entityUsageIndexService != null || classDetailCacheService != null) {
                List<String> affectedIris = ops.stream()
                    .flatMap(op -> Stream.of(op.iri(), op.parent(), op.target(), op.classIri()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
                if (entityUsageIndexService != null) entityUsageIndexService.invalidate(projectId, affectedIris);
                if (classDetailCacheService != null) classDetailCacheService.invalidate(projectId, affectedIris);
            }

            // Clear graph cache after mutations
            graphGeneratingService.clearGraphCache();
            if (visualizationController != null) {
                visualizationController.clearCache(projectId);
            }
            invalidateReasonerCaches(projectId);
            log.info("[MUTATION] Graph cache cleared after mutations");
            
            // For disjoint union mutations, verify the data was inserted
            if (ops.stream().anyMatch(op -> "addDisjointUnion".equals(op.type()))) {
                log.info("[MUTATION] Verifying DisjointUnion insertion...");
                for (MutationOp op : ops) {
                    if ("addDisjointUnion".equals(op.type())) {
                        String verifyQuery = PREFIXES + """
                            SELECT ?list ?member WHERE {
                              <%s> owl:disjointUnionOf ?list .
                              ?list rdf:rest*/rdf:first ?member .
                            }
                            """.formatted(op.iri());
                        log.info("[MUTATION] Verification query: {}", verifyQuery);
                        try {
                            var result = datasetService.execSelect(projectId, verifyQuery);
                            int count = 0;
                            while (result.hasNext()) {
                                var binding = result.next();
                                count++;
                                log.info("[MUTATION] Found member: {}", binding.getValue("member"));
                            }
                            log.info("[MUTATION] Verification found {} members", count);
                        } catch (Exception e) {
                            log.error("[MUTATION] Verification query failed: {}", e.getMessage());
                        }
                    }
                }
            }

            // Restrictions: fail fast if GraphDB did not persist the blank-node axiom.
            // Scope verification to the graph that was written (main vs draft), not the
            // user's draft read scope from SparqlQueryContext.
            String verifyGraphUri = draft
                    ? datasetService.getDraftGraphUri(projectId, userId)
                    : datasetService.getGraphUri(projectId);
            for (MutationOp op : ops) {
                if ("addDataRestriction".equals(op.type())) {
                    verifyRestrictionInserted(projectId, verifyGraphUri, op, true);
                } else if ("addObjectRestriction".equals(op.type())) {
                    verifyRestrictionInserted(projectId, verifyGraphUri, op, false);
                }
            }
        } catch (Exception e) {
            log.error("[MUTATION] ❌ Failed to apply mutations: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to apply mutations", e);
        }

        CompletableFuture.runAsync(() -> {
            Map<String, Object> meta = indexService.computeMetadata(projectId);
            metadataService.writeMeta(projectId, meta);
        }, metadataExecutor);
    }

    /**
     * This write went straight to Fuseki with no structured op for OwlApiMutationPatcher to
     * patch in-place, so OwlApiMutationCoordinator.afterMutation() will evict and rewarm — and on
     * desktop, DesktopOntologyLoader.findFastestParseSource() normally recovers the draft file
     * first, which never saw this write, silently losing it on rewarm (and the next deferred
     * Fuseki sync then overwrites Fuseki from that now-stale OWLAPI model, erasing it there too).
     * The dirty marker makes findFastestParseSource re-export fresh from Fuseki instead — the
     * same recovery path already built for post-import mutations, just never wired to a writer.
     */
    private void markDirtyAfterRawWrite(String projectId) {
        try {
            java.nio.file.Path dirtyMarker = storageManager.projectDir(projectId).resolve("ontology.dirty");
            java.nio.file.Files.createFile(dirtyMarker);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Already marked dirty by an earlier raw update since the last rewarm — fine.
        } catch (Exception e) {
            log.warn("[MUTATION] Could not create dirty marker for project {}: {}", projectId, e.getMessage());
        }
    }

    /**
     * Apply a pre-built SPARQL update for cases where the mutation payload needs
     * anonymous OWL class expressions generated by OWLAPI, such as DL Query
     * "Add to ontology".
     */
    public void applyRawUpdate(String projectId, String sparql) {
        applyRawUpdate(projectId, sparql, false, null);
    }

    /**
     * Apply a pre-built SPARQL update (optionally to the user's draft graph).
     */
    public void applyRawUpdate(String projectId, String sparql, boolean draft, String userId) {
        if (sparql == null || sparql.isBlank()) {
            log.warn("[MUTATION] Empty raw SPARQL update for project={}", projectId);
            return;
        }

        if (draft) {
            requireDraftCopyReady(projectId, userId);
            datasetService.execDraftUpdateCopyOnSwitch(projectId, userId, sparql);
        } else {
            datasetService.execUpdate(projectId, sparql);
            if (mainGraphRevisionService != null) {
                mainGraphRevisionService.incrementRevision(projectId);
            }
            markDirtyAfterRawWrite(projectId);
        }
        topLevelCacheService.evict(projectId);
        invalidatePublicCodeViewCache(projectId, draft);
        graphGeneratingService.clearGraphCache();
        if (visualizationController != null) {
            visualizationController.clearCache(projectId);
        }
         invalidateReasonerCaches(projectId);
        if (!draft && classDetailCacheService != null) {
             classDetailCacheService.dropAll(projectId);
        }

        if (!draft) {
            CompletableFuture.runAsync(() -> {
                Map<String, Object> meta = indexService.computeMetadata(projectId);
                metadataService.writeMeta(projectId, meta);
            }, metadataExecutor);
        }
    }

    public void makeSiblingsDisjoint(String projectId, List<String> classIds) {
        makeSiblingsDisjoint(projectId, classIds, false, null);
    }

    public void makeSiblingsDisjoint(String projectId, List<String> classIds, boolean draft, String userId) {
        if (classIds == null || classIds.size() < 2) {
            return;
        }

        log.info("[MUTATION] Cache cleared: topLevelClasses, classChildren");
        // Create pairwise disjoint axioms
        StringBuilder sparqlBuilder = new StringBuilder(PREFIXES);
        sparqlBuilder.append("\nINSERT DATA {\n");
        
        for (int i = 0; i < classIds.size(); i++) {
            for (int j = i + 1; j < classIds.size(); j++) {
                sparqlBuilder.append("  <")
                    .append(classIds.get(i))
                    .append("> owl:disjointWith <")
                    .append(classIds.get(j))
                    .append("> .\n");
            }
        }
        
        sparqlBuilder.append("}");
        
        String sparql = sparqlBuilder.toString();
        if (draft) {
            requireDraftCopyReady(projectId, userId);
            datasetService.execDraftUpdateCopyOnSwitch(projectId, userId, sparql);
        } else {
            datasetService.execUpdate(projectId, sparql);
            if (mainGraphRevisionService != null) {
                mainGraphRevisionService.incrementRevision(projectId);
            }
            markDirtyAfterRawWrite(projectId);
        }
        topLevelCacheService.evict(projectId);
        invalidatePublicCodeViewCache(projectId, draft);

        // Clear graph cache
        graphGeneratingService.clearGraphCache();
        if (visualizationController != null) {
            visualizationController.clearCache(projectId);
        }
        invalidateReasonerCaches(projectId);

        if (!draft) {
            CompletableFuture.runAsync(() -> {
                Map<String, Object> meta = indexService.computeMetadata(projectId);
                metadataService.writeMeta(projectId, meta);
            }, metadataExecutor);
        }
    }

    // These operation types use classIri() as their subject instead of iri()
    private static final java.util.Set<String> CLASS_IRI_OPS = java.util.Set.of(
        "addAxiom", "addObjectRestriction", "addDataRestriction",
        "deleteObjectRestriction", "deleteDataRestriction"
    );

    private String toUpdate(String projectId, MutationOp op) {
        String type = op.type();
        // Skip iri() validation for ops that use classIri() as their subject
        if (!CLASS_IRI_OPS.contains(type)) {
            if (op.iri() == null || op.iri().isBlank() || "null".equals(op.iri())) {
                log.error("[MUTATION] Invalid IRI for operation {}: iri={}", type, op.iri());
                throw new IllegalArgumentException("IRI cannot be null or empty for operation: " + type);
            }
        }

        if (type == null) throw new IllegalArgumentException("Unsupported op " + type);

        if (type.equals("createClass")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> a owl:Class .\n"
                + optionalLabel(op.iri(), op.label()) + "\n"
                + "<" + op.iri() + "> rdfs:subClassOf <" + op.parent() + "> .\n"
                + "}";
        } else if (type.equals("updateClassLabel")) {
            return "DELETE { <" + op.iri() + "> rdfs:label ?o }\n"
                + "INSERT { <" + op.iri() + "> rdfs:label " + literal(op.label()) + " }\n"
                + "WHERE  { OPTIONAL { <" + op.iri() + "> rdfs:label ?o } }";
        } else if (type.equals("deleteClass")) {
            // Dangling-expression cleanup MUST run first: SPARQL Update executes ';'-joined
            // statements sequentially against progressively mutated state, so if it ran after
            // the direct-triple deletes below, the filler triple (e.g. owl:someValuesFrom <iri>)
            // it searches for would already be gone and it would find nothing to clean up.
            return buildDeleteDanglingExpressionsSparql(op.iri(), CLASS_EXPR_FILLER_PREDICATES, CLASS_EXPR_ANCHOR_PREDICATES) + ";\n"
                + "DELETE { <" + op.iri() + "> ?p ?o } WHERE { <" + op.iri() + "> ?p ?o };\n"
                + "DELETE { ?s ?p <" + op.iri() + "> } WHERE { ?s ?p <" + op.iri() + "> }";
        } else if (type.equals("addAnnotation")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> <" + op.property() + "> " + annotationLiteral(op.value(), op.language(), op.datatype()) + " .\n"
                + "}";
        } else if (type.equals("updateAnnotation")) {
            String whereClause = op.oldValue() != null && !op.oldValue().isBlank()
                ? "{ <" + op.iri() + "> <" + op.property() + "> ?oldValue . FILTER(STR(?oldValue) = " + literal(op.oldValue()) + ") }"
                : "{ <" + op.iri() + "> <" + op.property() + "> ?oldValue }";
            return "DELETE { <" + op.iri() + "> <" + op.property() + "> ?oldValue }\n"
                + "INSERT { <" + op.iri() + "> <" + op.property() + "> " + annotationLiteral(op.value(), op.language(), op.datatype()) + " }\n"
                + "WHERE  " + whereClause;
        } else if (type.equals("deleteAnnotation")) {
            if (op.language() != null || op.datatype() != null) {
                // Language/datatype known: use exact DELETE DATA
                return "DELETE DATA {\n"
                    + "<" + op.iri() + "> <" + op.property() + "> " + annotationLiteral(op.value(), op.language(), op.datatype()) + " .\n"
                    + "}";
            }
            // Language/datatype unknown: match any literal with the same string value
            return "DELETE { <" + op.iri() + "> <" + op.property() + "> ?v }\n"
                + "WHERE  { <" + op.iri() + "> <" + op.property() + "> ?v . FILTER(STR(?v) = " + literal(op.value()) + ") }";
        } else if (type.equals("addSubClassOf")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> rdfs:subClassOf <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteSubClassOf")) {
            // Handle both named IRIs and blank nodes
            if (isBlankNodeRef(op.target())) {
                // For blank nodes, use DELETE/WHERE pattern
                return "DELETE { <" + op.iri() + "> rdfs:subClassOf ?target }\n"
                    + "WHERE { <" + op.iri() + "> rdfs:subClassOf ?target .\n"
                    + "  FILTER(isBlank(?target) && str(?target) = \"" + op.target() + "\") }";
            } else {
                // Pattern-match delete, not DELETE DATA — DELETE DATA silently no-ops
                // on any non-exact triple match, which made this axiom effectively
                // undeletable in practice with no error surfaced anywhere.
                return "DELETE { <" + op.iri() + "> rdfs:subClassOf <" + op.target() + "> }\n"
                    + "WHERE { <" + op.iri() + "> rdfs:subClassOf <" + op.target() + "> }";
            }
        } else if (type.equals("updateSubClassOf")) {
            // Update operation: replace old target with new target
            // op.value contains the old target IRI, op.target contains the new target IRI
            return "DELETE { <" + op.iri() + "> rdfs:subClassOf <" + op.value() + "> }\n"
                + "INSERT { <" + op.iri() + "> rdfs:subClassOf <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> rdfs:subClassOf <" + op.value() + "> }";
        } else if (type.equals("addEquivalentClass")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> owl:equivalentClass <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteEquivalentClass")) {
            // Handle both named IRIs and blank nodes
            if (isBlankNodeRef(op.target())) {
                // For blank nodes, use DELETE/WHERE pattern
                return "DELETE { <" + op.iri() + "> owl:equivalentClass ?target }\n"
                    + "WHERE { <" + op.iri() + "> owl:equivalentClass ?target .\n"
                    + "  FILTER(isBlank(?target) && str(?target) = \"" + op.target() + "\") }";
            } else {
                // Pattern-match delete, not DELETE DATA — see deleteSubClassOf.
                return "DELETE { <" + op.iri() + "> owl:equivalentClass <" + op.target() + "> }\n"
                    + "WHERE { <" + op.iri() + "> owl:equivalentClass <" + op.target() + "> }";
            }
        } else if (type.equals("updateEquivalentClass")) {
            // Update operation: replace old target with new target
            // op.value contains the old target IRI, op.target contains the new target IRI
            return "DELETE { <" + op.iri() + "> owl:equivalentClass <" + op.value() + "> }\n"
                + "INSERT { <" + op.iri() + "> owl:equivalentClass <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> owl:equivalentClass <" + op.value() + "> }";
        } else if (type.equals("addDisjointWith")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> owl:disjointWith <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteDisjointWith")) {
            // Handle both named IRIs and blank nodes
            if (isBlankNodeRef(op.target())) {
                // For blank nodes, use DELETE/WHERE pattern
                return "DELETE { <" + op.iri() + "> owl:disjointWith ?target }\n"
                    + "WHERE { <" + op.iri() + "> owl:disjointWith ?target .\n"
                    + "  FILTER(isBlank(?target) && str(?target) = \"" + op.target() + "\") }";
            } else {
                // Pattern-match delete, not DELETE DATA — see deleteSubClassOf.
                return "DELETE { <" + op.iri() + "> owl:disjointWith <" + op.target() + "> }\n"
                    + "WHERE { <" + op.iri() + "> owl:disjointWith <" + op.target() + "> }";
            }
        } else if (type.equals("updateDisjointWith")) {
            // Update operation: replace old target with new target
            // op.value contains the old target IRI, op.target contains the new target IRI
            return "DELETE { <" + op.iri() + "> owl:disjointWith <" + op.value() + "> }\n"
                + "INSERT { <" + op.iri() + "> owl:disjointWith <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> owl:disjointWith <" + op.value() + "> }";
        } else if (type.equals("createIndividual")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> a owl:NamedIndividual .\n"
                + optionalLabel(op.iri(), op.label()) + "\n"
                + "<" + op.iri() + "> a <" + op.classIri() + "> .\n"
                + "}";
        } else if (type.equals("deleteIndividual")) {
            return "DELETE { <" + op.iri() + "> ?p ?o } WHERE { <" + op.iri() + "> ?p ?o };\n"
                + "DELETE { ?s ?p <" + op.iri() + "> } WHERE { ?s ?p <" + op.iri() + "> }";
        } else if (type.equals("createObjectProperty")) {
            return createPropertySparql(op.iri(), op.label(), op.parent(), "owl:ObjectProperty");
        } else if (type.equals("createDataProperty")) {
            return createPropertySparql(op.iri(), op.label(), op.parent(), "owl:DatatypeProperty");
        } else if (type.equals("createAnnotationProperty")) {
            return createPropertySparql(op.iri(), op.label(), op.parent(), "owl:AnnotationProperty");
        } else if (type.equals("deleteObjectProperty")) {
            // Cleanup runs first — see the comment on deleteClass above for why.
            return buildDeleteDanglingExpressionsSparql(op.iri(), "owl:onProperty", PROPERTY_EXPR_ANCHOR_PREDICATES) + ";\n"
                + "DELETE { <" + op.iri() + "> ?p ?o } WHERE { <" + op.iri() + "> ?p ?o };\n"
                + "DELETE { ?s <" + op.iri() + "> ?o } WHERE { ?s <" + op.iri() + "> ?o };\n"
                + "DELETE { ?s ?p <" + op.iri() + "> } WHERE { ?s ?p <" + op.iri() + "> }";
        } else if (type.equals("deleteDataProperty")) {
            // Cleanup runs first — see the comment on deleteClass above for why.
            return buildDeleteDanglingExpressionsSparql(op.iri(), "owl:onProperty", PROPERTY_EXPR_ANCHOR_PREDICATES) + ";\n"
                + "DELETE { <" + op.iri() + "> ?p ?o } WHERE { <" + op.iri() + "> ?p ?o };\n"
                + "DELETE { ?s <" + op.iri() + "> ?o } WHERE { ?s <" + op.iri() + "> ?o };\n"
                + "DELETE { ?s ?p <" + op.iri() + "> } WHERE { ?s ?p <" + op.iri() + "> }";
        } else if (type.equals("deleteAnnotationProperty")) {
            return "DELETE { <" + op.iri() + "> ?p ?o } WHERE { <" + op.iri() + "> ?p ?o };\n"
                + "DELETE { ?s <" + op.iri() + "> ?o } WHERE { ?s <" + op.iri() + "> ?o };\n"
                + "DELETE { ?s ?p <" + op.iri() + "> } WHERE { ?s ?p <" + op.iri() + "> }";
        } else if (type.equals("addPropertyDomain")) {
            if (op.restrictionType() != null) {
                // Domain is a restriction
                boolean isDataRestriction = "DataRestriction".equals(op.axiomType());
                return buildRestrictionInsertData(
                        op.iri(), "rdfs:domain", op.property(), op.restrictionType(), op.target(),
                        op.cardinality(), isDataRestriction);
            } else {
                return "INSERT DATA {\n"
                    + "<" + op.iri() + "> rdfs:domain <" + op.target() + "> .\n"
                    + "}";
            }
        } else if (type.equals("deletePropertyDomain")) {
            if (op.target() != null && op.target().contains("|||")) {
                return buildDeletePropertyRestrictionSparql(op.iri(), "rdfs:domain", op.target());
            }
            // Pattern-match delete, not DELETE DATA — see deleteSubClassOf.
            return "DELETE { <" + op.iri() + "> rdfs:domain <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> rdfs:domain <" + op.target() + "> }";
        } else if (type.equals("addPropertyRange")) {
            if (op.restrictionType() != null) {
                // Range is a restriction
                boolean isDataRestriction = "DataRestriction".equals(op.axiomType());
                return buildRestrictionInsertData(
                        op.iri(), "rdfs:range", op.property(), op.restrictionType(), op.target(),
                        op.cardinality(), isDataRestriction);
            } else if (op.target() != null && op.target().contains("[")) {
                String drSparql = buildDatatypeRestrictionSparql(op.iri(), op.target(), "rdfs:range");
                if (!drSparql.isEmpty()) return drSparql;
            }
            String rangeTarget = op.target();
            if (rangeTarget != null && !rangeTarget.startsWith("http") && rangeTarget.contains(":")) {
                rangeTarget = resolvePrefixedIri(rangeTarget);
            }
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> rdfs:range <" + rangeTarget + "> .\n"
                + "}";
        } else if (type.equals("deletePropertyRange")) {
            if (op.target() != null && op.target().contains("|||")) {
                return buildDeletePropertyRestrictionSparql(op.iri(), "rdfs:range", op.target());
            }
            if (op.target() != null && op.target().contains("[")) {
                return buildDeleteDatatypeRestrictionSparql(op.iri(), "rdfs:range");
            }
            // Pattern-match delete, not DELETE DATA — see deleteSubClassOf.
            return "DELETE { <" + op.iri() + "> rdfs:range <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> rdfs:range <" + op.target() + "> }";
        } else if (type.equals("addDatatypeDefinition")) {
            return buildDatatypeDefinitionSparql(op.iri(), op.value());
        } else if (type.equals("deleteDatatypeDefinition")) {
            return buildDeleteDatatypeDefinitionSparql(op.iri());
        } else if (type.equals("addSubPropertyOf")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> rdfs:subPropertyOf <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteSubPropertyOf")) {
            // Pattern-match delete, not DELETE DATA — see deleteSubClassOf.
            return "DELETE { <" + op.iri() + "> rdfs:subPropertyOf <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> rdfs:subPropertyOf <" + op.target() + "> }";
        } else if (type.equals("updateSubPropertyOf")) {
            // op.value() = old target IRI, op.target() = new target IRI. Atomic
            // DELETE{...}WHERE{...} instead of DELETE DATA — see updateSubClassOf.
            return "DELETE { <" + op.iri() + "> rdfs:subPropertyOf <" + op.value() + "> }\n"
                + "INSERT { <" + op.iri() + "> rdfs:subPropertyOf <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> rdfs:subPropertyOf <" + op.value() + "> }";
        } else if (type.equals("updatePropertyDomain")) {
            return "DELETE { <" + op.iri() + "> rdfs:domain <" + op.value() + "> }\n"
                + "INSERT { <" + op.iri() + "> rdfs:domain <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> rdfs:domain <" + op.value() + "> }";
        } else if (type.equals("updatePropertyRange")) {
            return "DELETE { <" + op.iri() + "> rdfs:range <" + op.value() + "> }\n"
                + "INSERT { <" + op.iri() + "> rdfs:range <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> rdfs:range <" + op.value() + "> }";
        } else if (type.equals("updateInverseProperty")) {
            // owl:inverseOf is symmetric — swap both directions atomically.
            return "DELETE { <" + op.iri() + "> owl:inverseOf <" + op.value() + "> . <" + op.value() + "> owl:inverseOf <" + op.iri() + "> }\n"
                + "INSERT { <" + op.iri() + "> owl:inverseOf <" + op.target() + "> . <" + op.target() + "> owl:inverseOf <" + op.iri() + "> }\n"
                + "WHERE { <" + op.iri() + "> owl:inverseOf <" + op.value() + "> }";
        } else if (type.equals("updateDisjointProperty")) {
            return "DELETE { <" + op.iri() + "> owl:propertyDisjointWith <" + op.value() + "> }\n"
                + "INSERT { <" + op.iri() + "> owl:propertyDisjointWith <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> owl:propertyDisjointWith <" + op.value() + "> }";
        } else if (type.equals("updateEquivalentProperty")) {
            return "DELETE { <" + op.iri() + "> owl:equivalentProperty <" + op.value() + "> }\n"
                + "INSERT { <" + op.iri() + "> owl:equivalentProperty <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> owl:equivalentProperty <" + op.value() + "> }";
        } else if (type.equals("addInverseProperty")) {
            // owl:inverseOf is symmetric in OWL — insert both directions so both
            // properties show each other as inverse (keeps inverses bidirectional).
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> owl:inverseOf <" + op.target() + "> .\n"
                + "<" + op.target() + "> owl:inverseOf <" + op.iri() + "> .\n"
                + "}";
        } else if (type.equals("deleteInverseProperty")) {
            // Pattern-match delete, not DELETE DATA — see deleteSubClassOf.
            return "DELETE { <" + op.iri() + "> owl:inverseOf <" + op.target() + "> . <" + op.target() + "> owl:inverseOf <" + op.iri() + "> }\n"
                + "WHERE { <" + op.iri() + "> owl:inverseOf <" + op.target() + "> }";
        } else if (type.equals("addDisjointProperty")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> owl:propertyDisjointWith <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteDisjointProperty")) {
            // Pattern-match delete, not DELETE DATA — see deleteSubClassOf.
            return "DELETE { <" + op.iri() + "> owl:propertyDisjointWith <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> owl:propertyDisjointWith <" + op.target() + "> }";
        } else if (type.equals("addEquivalentProperty")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> owl:equivalentProperty <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteEquivalentProperty")) {
            // Pattern-match delete, not DELETE DATA — see deleteSubClassOf.
            return "DELETE { <" + op.iri() + "> owl:equivalentProperty <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> owl:equivalentProperty <" + op.target() + "> }";
        } else if (type.equals("addCharacteristic")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> a <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteCharacteristic")) {
            // Pattern-match delete, not DELETE DATA — see deleteSubClassOf.
            return "DELETE { <" + op.iri() + "> a <" + op.target() + "> }\n"
                + "WHERE { <" + op.iri() + "> a <" + op.target() + "> }";
        } else if (type.equals("addAxiom")) {
            // Back-compat/generic axiom support used by some UI components.
            // We only support a small set of axiom "kinds" that can be expressed as direct RDF triples.
            //
            // Payload convention from the webview:
            // - op.classIri(): subject (class or individual)
            // - op.target(): object (IRI) OR expression string
            // - op.value(): axiom kind (e.g., SubClassOf, EquivalentTo, SameIndividual, DifferentIndividuals, ClassAssertion)
            String axiomKind = op.value();
            String subject = op.classIri();
            String object = op.target();

            if (axiomKind == null || axiomKind.isBlank()) {
                log.warn("[MUTATION] addAxiom missing axiom kind: {}", op);
                return "";
            }
            if (subject == null || subject.isBlank() || object == null || object.isBlank()) {
                log.warn("[MUTATION] addAxiom missing subject/object: {}", op);
                return "";
            }

            return switch (axiomKind) {
                case "SameIndividual" ->
                    "INSERT DATA {\n"
                        + "<" + subject + "> owl:sameAs <" + object + "> .\n"
                        + "<" + object + "> owl:sameAs <" + subject + "> .\n"
                        + "}";
                case "DifferentIndividuals" ->
                    "INSERT DATA {\n"
                        + "<" + subject + "> owl:differentFrom <" + object + "> .\n"
                        + "<" + object + "> owl:differentFrom <" + subject + "> .\n"
                        + "}";
                case "ClassAssertion" ->
                    "INSERT DATA {\n"
                        + "<" + subject + "> a <" + object + "> .\n"
                        + "}";
                case "EquivalentTo", "SubClassOf", "DisjointWith" -> {
                    // Without a Manchester parser, only accept a direct IRI as the RHS.
                    if (object.startsWith("http://") || object.startsWith("https://") || object.startsWith("urn:")) {
                        String predicate = getAxiomPredicate(axiomKind);
                        yield "INSERT DATA {\n"
                            + "<" + subject + "> " + predicate + " <" + object + "> .\n"
                            + "}";
                    }
                    log.warn("[MUTATION] addAxiom unsupported (non-IRI RHS) kind={} target={}", axiomKind, object);
                    yield "";
                }
                default -> {
                    log.warn("[MUTATION] addAxiom unsupported kind={}", axiomKind);
                    yield "";
                }
            };
        } else if (type.equals("addDisjointUnion")) {
            log.info("[MUTATION] Processing addDisjointUnion: iri={}, value={}", op.iri(), op.value());
            String[] memberIris = op.value() != null ? op.value().split(",") : new String[0];
            log.info("[MUTATION] Parsed {} member IRIs", memberIris.length);
            if (memberIris.length < 2) {
                log.warn("[MUTATION] DisjointUnion requires at least 2 member classes, got {}", memberIris.length);
                return "";
            }
            String sparql = buildDisjointUnionSparql(op.iri(), memberIris);
            log.info("[MUTATION] Generated DisjointUnion SPARQL: {}", sparql);
            if (sparql == null || sparql.trim().isEmpty()) {
                log.error("[MUTATION] Generated empty SPARQL for DisjointUnion!");
                return "";
            }
            return sparql;
        } else if (type.equals("deleteDisjointUnion")) {
            log.info("[MUTATION] Processing deleteDisjointUnion: iri={}, target={}", op.iri(), op.target());
            return buildDeleteDisjointUnionSparql(op.iri(), op.target());
        } else if (type.equals("addHasKey")) {
            String[] propertyIris = op.value() != null ? op.value().split(",") : new String[0];
            if (propertyIris.length < 1) {
                log.warn("[MUTATION] HasKey requires at least 1 property");
                return "";
            }
            return buildHasKeySparql(op.iri(), propertyIris);
        } else if (type.equals("deleteHasKey")) {
            return buildDeleteHasKeySparql(op.iri(), op.target());
        } else if (type.equals("addIntersection")) {
            String[] memberIris = op.value() != null ? op.value().split(",") : new String[0];
            if (memberIris.length < 2) {
                log.warn("[MUTATION] Intersection requires at least 2 member classes");
                return "";
            }
            return buildIntersectionSparql(op.iri(), memberIris, op.axiomType());
        } else if (type.equals("deleteIntersection")) {
            return buildDeleteComplexExpressionSparql(op.iri(), op.target(), op.axiomType());
        } else if (type.equals("addGCAIntersection")) {
            // General Class Axiom: (A and B) SubClassOf <classIri>
            // The anonymous intersection is the SUBJECT of SubClassOf
            String[] memberIris = op.value() != null ? op.value().split(",") : new String[0];
            if (memberIris.length < 2) {
                log.warn("[MUTATION] GCA intersection requires at least 2 member classes");
                return "";
            }
            return buildGCAIntersectionSparql(op.iri(), memberIris);
        } else if (type.equals("addGCAUnion")) {
            // General Class Axiom: (A or B) SubClassOf <classIri>
            String[] memberIris = op.value() != null ? op.value().split(",") : new String[0];
            if (memberIris.length < 2) {
                log.warn("[MUTATION] GCA union requires at least 2 member classes");
                return "";
            }
            return buildGCAUnionSparql(op.iri(), memberIris);
        } else if (type.equals("addUnion")) {
            String[] memberIris = op.value() != null ? op.value().split(",") : new String[0];
            if (memberIris.length < 2) {
                log.warn("[MUTATION] Union requires at least 2 member classes");
                return "";
            }
            return buildUnionSparql(op.iri(), memberIris, op.axiomType());
        } else if (type.equals("deleteUnion")) {
            return buildDeleteComplexExpressionSparql(op.iri(), op.target(), op.axiomType());
        } else if (type.equals("addComplement")) {
            return buildComplementSparql(op.iri(), op.target(), op.axiomType());
        } else if (type.equals("deleteComplement")) {
            return buildDeleteComplexExpressionSparql(op.iri(), op.target(), op.axiomType());
        } else if (type.equals("addOneOf")) {
            String[] individualIris = op.value() != null ? op.value().split(",") : new String[0];
            if (individualIris.length < 1) {
                log.warn("[MUTATION] OneOf requires at least 1 individual");
                return "";
            }
            return buildOneOfSparql(op.iri(), individualIris, op.axiomType());
        } else if (type.equals("deleteOneOf")) {
            return buildDeleteComplexExpressionSparql(op.iri(), op.target(), op.axiomType());
        } else if (type.equals("addObjectRestriction")) {
            return buildRestrictionSparql(op, false);
        } else if (type.equals("deleteObjectRestriction")) {
            return buildDeleteRestrictionSparql(op, false);
        } else if (type.equals("addDataRestriction")) {
            return buildRestrictionSparql(op, true);
        } else if (type.equals("deleteDataRestriction")) {
            return buildDeleteRestrictionSparql(op, true);
        } else if (type.equals("createDatatype")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> a rdfs:Datatype .\n"
                + optionalLabel(op.iri(), op.label()) + "\n"
                + "}";
        } else if (type.equals("deleteDatatype")) {
            // Cleanup runs first — see the comment on deleteClass above for why.
            return buildDeleteDanglingExpressionsSparql(op.iri(), "owl:someValuesFrom|owl:allValuesFrom", DATATYPE_EXPR_ANCHOR_PREDICATES) + ";\n"
                + "DELETE { <" + op.iri() + "> ?p ?o } WHERE { <" + op.iri() + "> ?p ?o };\n"
                + "DELETE { ?s ?p <" + op.iri() + "> } WHERE { ?s ?p <" + op.iri() + "> }";
        } else if (type.equals("addObjectPropertyAssertion")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> <" + op.property() + "> <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteObjectPropertyAssertion")) {
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> <" + op.property() + "> <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("addDataPropertyAssertion")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> <" + op.property() + "> "
                + annotationLiteral(op.value(), op.language(), op.datatype()) + " .\n"
                + "}";
        } else if (type.equals("deleteDataPropertyAssertion")) {
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> <" + op.property() + "> " + literal(op.value()) + " .\n"
                + "}";
        } else if (type.equals("addNegativeObjectPropertyAssertion")) {
            String npaIri = negativePropertyAssertionIri(op, true);
            return "INSERT DATA {\n"
                + "<" + npaIri + "> a owl:NegativePropertyAssertion ;\n"
                + "  owl:sourceIndividual <" + op.iri() + "> ;\n"
                + "  owl:assertionProperty <" + op.property() + "> ;\n"
                + "  owl:targetIndividual <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteNegativeObjectPropertyAssertion")) {
            String npaIri = negativePropertyAssertionIri(op, true);
            return "DELETE WHERE { <" + npaIri + "> ?p ?o }";
        } else if (type.equals("addNegativeDataPropertyAssertion")) {
            String npaIri = negativePropertyAssertionIri(op, false);
            return "INSERT DATA {\n"
                + "<" + npaIri + "> a owl:NegativePropertyAssertion ;\n"
                + "  owl:sourceIndividual <" + op.iri() + "> ;\n"
                + "  owl:assertionProperty <" + op.property() + "> ;\n"
                + "  owl:targetValue " + literal(op.value()) + " .\n"
                + "}";
        } else if (type.equals("deleteNegativeDataPropertyAssertion")) {
            String npaIri = negativePropertyAssertionIri(op, false);
            return "DELETE WHERE { <" + npaIri + "> ?p ?o }";
        } else if (type.equals("addSameIndividual")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> owl:sameAs <" + op.target() + "> .\n"
                + "<" + op.target() + "> owl:sameAs <" + op.iri() + "> .\n"
                + "}";
        } else if (type.equals("deleteSameIndividual")) {
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> owl:sameAs <" + op.target() + "> .\n"
                + "<" + op.target() + "> owl:sameAs <" + op.iri() + "> .\n"
                + "}";
        } else if (type.equals("addDifferentIndividual")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> owl:differentFrom <" + op.target() + "> .\n"
                + "<" + op.target() + "> owl:differentFrom <" + op.iri() + "> .\n"
                + "}";
        } else if (type.equals("deleteDifferentIndividual")) {
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> owl:differentFrom <" + op.target() + "> .\n"
                + "<" + op.target() + "> owl:differentFrom <" + op.iri() + "> .\n"
                + "}";
        } else if (type.equals("addPropertyChain")) {
            String[] chainProps = op.value() != null ? op.value().split(" o ") : new String[0];
            if (chainProps.length < 2) {
                log.warn("[MUTATION] Property chain requires at least 2 properties, got: {}", op.value());
                return "";
            }
            return buildPropertyChainSparql(op.iri(), chainProps);
        } else if (type.equals("deletePropertyChain")) {
            String[] chainProps = op.value() != null ? op.value().split(" o ") : new String[0];
            if (chainProps.length < 2) {
                log.warn("[MUTATION] Property chain delete requires at least 2 properties, got: {}", op.value());
                return "";
            }
            return buildDeletePropertyChainSparql(op.iri(), chainProps);
        } else if (type.equals("addClassAssertion")) {
            // Add rdf:type assertion to an existing individual
            if (op.classIri() == null) return "";
            String classExpr = buildClassExpressionSparql(projectId, op.classIri());
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> a " + classExpr + " .\n"
                + "}";
        } else if (type.equals("removeClassAssertion")) {
            // Remove rdf:type assertion from an individual
            if (op.classIri() == null) return "";
            String classExpr = buildClassExpressionSparql(projectId, op.classIri());
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> a " + classExpr + " .\n"
                + "}";
        } else if (type.equals("deleteAxiom")) {
            return buildDeleteBlankNodeAxiomSparql(op.iri(), op.ancestorIri());
        } else {
            throw new IllegalArgumentException("Unsupported op " + op.type());
        }
    }

    /**
     * True when {@code target} is NOT a resolvable absolute IRI — i.e. it's a blank-node
     * reference (a complex/anonymous class expression's row id). RDF4J's {@code BNode.stringValue()}
     * returns the bare internal id (e.g. "b0", not "_:b0" — see buildDeleteBlankNodeAxiomSparql),
     * so checking for a literal "_:" prefix here never matched anything: every delete of an
     * anonymous SubClassOf/EquivalentTo/DisjointWith superclass silently took the named-IRI
     * DELETE DATA branch below instead, which can't match a blank node and deletes nothing.
     */
    private boolean isBlankNodeRef(String target) {
        return target == null || !(target.startsWith("http://") || target.startsWith("https://"));
    }

    private String optionalLabel(String iri, String label) {
        return (label == null || label.isBlank())
                ? ""
                : "<%s> rdfs:label %s .".formatted(iri, literal(label));
    }

    private String literal(String value) {
        if (value == null) {
            return "\"\"";
        }
        // Properly escape special characters in SPARQL string literals
        String escaped = value
            .replace("\\", "\\\\")   // Backslash must be first
            .replace("\"", "\\\"")   // Double quote
            .replace("\n", "\\n")    // Newline
            .replace("\r", "\\r")    // Carriage return
            .replace("\t", "\\t");   // Tab
        return "\"%s\"".formatted(escaped);
    }

    private String annotationLiteral(String value, String language, String datatype) {
        String base = literal(value);
        if (language != null && !language.isBlank()) {
            return base + "@" + language.trim().toLowerCase();
        }
        if (datatype != null && !datatype.isBlank()) {
            // Support both full IRI and prefixed form (e.g. "xsd:boolean")
            String dt = datatype.startsWith("http") ? "<" + datatype + ">" : datatype;
            return base + "^^" + dt;
        }
        return base;
    }

    private String negativePropertyAssertionIri(MutationOp op, boolean isObjectTarget) {
        String raw = isObjectTarget
            ? "%s|%s|%s".formatted(op.iri(), op.property(), op.target())
            : "%s|%s|%s".formatted(op.iri(), op.property(), op.value());

        String hash = sha256Hex(raw);
        return "http://ontocode.org/axiom/negativePropertyAssertion/" + hash;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA-256", e);
        }
    }

    // Helper method to generate an rdfs:label triple if label is present
    // (Duplicate removed)

    /**
     * Create SPARQL for property creation - only adds subPropertyOf if parent is not a top-level property
     */
    private String createPropertySparql(String iri, String label, String parent, String propertyType) {
        log.info("[MUTATION] createPropertySparql called:");
        log.info("[MUTATION]   IRI: {}", iri);
        log.info("[MUTATION]   Label: {}", label);
        log.info("[MUTATION]   Parent: {}", parent);
        log.info("[MUTATION]   PropertyType: {}", propertyType);
        
        boolean hasRealParent = parent != null && !parent.isEmpty() 
            && !parent.contains("topObjectProperty") 
            && !parent.contains("topDataProperty")
            && !parent.equals("http://www.w3.org/2002/07/owl#topObjectProperty")
            && !parent.equals("http://www.w3.org/2002/07/owl#topDataProperty");
           
        
        log.info("[MUTATION]   hasRealParent: {}", hasRealParent);
        
        String sparql;
        if (hasRealParent) {
            sparql = """
                INSERT DATA {
                  <%s> a %s .
                  %s
                  <%s> rdfs:subPropertyOf <%s> .
                }
                """.formatted(iri, propertyType, optionalLabel(iri, label), iri, parent);
        } else {
            sparql = """
                INSERT DATA {
                  <%s> a %s .
                  %s
                }
                """.formatted(iri, propertyType, optionalLabel(iri, label));
        }
        
        log.info("[MUTATION]   Generated SPARQL: {}", sparql);
        return sparql;
    }

    /**
     * Delete a restriction blank node connected via rdfs:range or rdfs:domain.
     * encodedTarget format: display|||restrictionType|||onPropertyIri|||fillerIri|||cardinality
     */
    private String buildDeletePropertyRestrictionSparql(String propertyIri, String axiomPredicate, String encodedTarget) {
        String[] parts = encodedTarget.split("\\|\\|\\|", -1);
        if (parts.length < 4) {
            log.warn("[MUTATION] Invalid encoded restriction target (expected >=4 parts): {}", encodedTarget);
            return "";
        }
        String restrictionType = parts[1];
        String onPropIri = parts[2];
        String fillerIri = parts[3];
        String card = parts.length > 4 ? parts[4] : "";

        String fillerPattern = switch (restrictionType) {
            case "some" -> "?r owl:someValuesFrom <" + fillerIri + "> .";
            case "only" -> "?r owl:allValuesFrom <" + fillerIri + "> .";
            case "value" -> "?r owl:hasValue <" + fillerIri + "> .";
            case "min" -> "?r owl:minQualifiedCardinality \"" + card + "\"^^xsd:nonNegativeInteger .\n      ?r owl:onClass <" + fillerIri + "> .";
            case "max" -> "?r owl:maxQualifiedCardinality \"" + card + "\"^^xsd:nonNegativeInteger .\n      ?r owl:onClass <" + fillerIri + "> .";
            case "exactly" -> "?r owl:qualifiedCardinality \"" + card + "\"^^xsd:nonNegativeInteger .\n      ?r owl:onClass <" + fillerIri + "> .";
            default -> "";
        };

        if (fillerPattern.isEmpty()) {
            log.warn("[MUTATION] Unknown restriction type in encoded target: {}", restrictionType);
            return "";
        }

        String sparql = PREFIXES + """
            DELETE {
              <%s> %s ?r .
              ?r ?p ?o .
            }
            WHERE {
              <%s> %s ?r .
              ?r a owl:Restriction .
              ?r owl:onProperty <%s> .
              %s
              ?r ?p ?o .
              FILTER(isBlank(?r))
            }
            """.formatted(propertyIri, axiomPredicate, propertyIri, axiomPredicate, onPropIri, fillerPattern);

        log.info("[MUTATION] buildDeletePropertyRestrictionSparql: {}", sparql);
        return sparql;
    }

    /**
     * Build SPARQL INSERT to add an OWL restriction (object or data restriction)
     * Uses OWL 2 RDF syntax with blank nodes via INSERT WHERE pattern
     *
     * @param op MutationOp containing restriction details
     * @param isDataRestriction true for data restrictions, false for object restrictions
     * @return SPARQL UPDATE string
     */
    private String buildRestrictionBody(String propertyIri, String restrictionType, String fillerIri, Integer cardinality, boolean isDataRestriction) {
        return switch (restrictionType) {
            case "some" -> {
                String fillerPredicate = "owl:someValuesFrom";
                yield """
                    [
                      a owl:Restriction ;
                      owl:onProperty <%s> ;
                      %s <%s>
                    ]""".formatted(propertyIri, fillerPredicate, fillerIri);
            }
            case "only" -> {
                String fillerPredicate = "owl:allValuesFrom";
                yield """
                    [
                      a owl:Restriction ;
                      owl:onProperty <%s> ;
                      %s <%s>
                    ]""".formatted(propertyIri, fillerPredicate, fillerIri);
            }
            case "value" -> """
                [
                  a owl:Restriction ;
                  owl:onProperty <%s> ;
                  owl:hasValue <%s>
                ]""".formatted(propertyIri, fillerIri);
            case "min" -> {
                int card = cardinality != null ? cardinality : 1;
                String onClassPredicate = isDataRestriction ? "owl:onDataRange" : "owl:onClass";
                yield """
                [
                  a owl:Restriction ;
                  owl:onProperty <%s> ;
                  owl:minQualifiedCardinality "%d"^^xsd:nonNegativeInteger ;
                  %s <%s>
                ]""".formatted(propertyIri, card, onClassPredicate, fillerIri);
            }
            case "max" -> {
                int card = cardinality != null ? cardinality : 1;
                String onClassPredicate = isDataRestriction ? "owl:onDataRange" : "owl:onClass";
                yield """
                [
                  a owl:Restriction ;
                  owl:onProperty <%s> ;
                  owl:maxQualifiedCardinality "%d"^^xsd:nonNegativeInteger ;
                  %s <%s>
                ]""".formatted(propertyIri, card, onClassPredicate, fillerIri);
            }
            case "exactly" -> {
                int card = cardinality != null ? cardinality : 1;
                String onClassPredicate = isDataRestriction ? "owl:onDataRange" : "owl:onClass";
                yield """
                [
                  a owl:Restriction ;
                  owl:onProperty <%s> ;
                  owl:qualifiedCardinality "%d"^^xsd:nonNegativeInteger ;
                  %s <%s>
                ]""".formatted(propertyIri, card, onClassPredicate, fillerIri);
            }
            default -> "";
        };
    }

    private String buildRestrictionSparql(MutationOp op, boolean isDataRestriction) {
        String classIri = op.iri();
        String propertyIri = op.property();
        String restrictionType = op.restrictionType();
        String fillerIri = op.target();
        Integer cardinality = op.cardinality();
        String axiomType = op.axiomType();
        
        log.info("[MUTATION] buildRestrictionSparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   propertyIri: {}", propertyIri);
        log.info("[MUTATION]   restrictionType: {}", restrictionType);
        log.info("[MUTATION]   fillerIri: {}", fillerIri);
        log.info("[MUTATION]   cardinality: {}", cardinality);
        log.info("[MUTATION]   axiomType: {}", axiomType);
        log.info("[MUTATION]   isDataRestriction: {}", isDataRestriction);
        
        // Validate required fields
        if (classIri == null || propertyIri == null || restrictionType == null || fillerIri == null) {
            log.error("[MUTATION] Missing required fields for restriction: classIri={}, propertyIri={}, restrictionType={}, fillerIri={}",
                classIri, propertyIri, restrictionType, fillerIri);
            return "";
        }
        
        // Determine the axiom predicate (default to SubClassOf if axiomType is null)
        String axiomPredicate = "rdfs:subClassOf";
        if (axiomType != null) {
            axiomPredicate = switch (axiomType) {
                case "EquivalentTo" -> "owl:equivalentClass";
                case "DisjointWith" -> "owl:disjointWith";
                default -> "rdfs:subClassOf";
            };
        }
        
        String sparql = buildRestrictionInsertData(
                classIri, axiomPredicate, propertyIri, restrictionType, fillerIri, cardinality, isDataRestriction);
        if (sparql.isEmpty()) {
            log.warn("[MUTATION] Unknown restriction type: {}", restrictionType);
            return "";
        }
        log.info("[MUTATION]   Generated restriction SPARQL: {}", sparql);
        return sparql;
    }

    /**
     * Build INSERT DATA for a class/property restriction using explicit blank-node
     * triples ({@code _:ontocodeR}). GraphDB/RDF4J often rejects or mishandles Turtle
     * {@code [ ... ]} blank-node syntax inside INSERT DATA after graph injection.
     */
    private String buildRestrictionInsertData(String subjectIri, String axiomPredicate,
                                              String propertyIri, String restrictionType,
                                              String fillerIri, Integer cardinality,
                                              boolean isDataRestriction) {
        if (subjectIri == null || propertyIri == null || restrictionType == null || fillerIri == null) {
            return "";
        }
        String bn = "_:ontocodeR" + java.util.UUID.randomUUID().toString().replace("-", "");
        StringBuilder triples = new StringBuilder();
        triples.append("<").append(subjectIri).append("> ").append(axiomPredicate).append(" ").append(bn).append(" .\n");
        triples.append(bn).append(" a owl:Restriction .\n");
        triples.append(bn).append(" owl:onProperty <").append(propertyIri).append("> .\n");

        switch (restrictionType) {
            case "some" -> triples.append(bn).append(" owl:someValuesFrom <").append(fillerIri).append("> .\n");
            case "only" -> triples.append(bn).append(" owl:allValuesFrom <").append(fillerIri).append("> .\n");
            case "value" -> triples.append(bn).append(" owl:hasValue <").append(fillerIri).append("> .\n");
            case "min", "max", "exactly" -> {
                int card = cardinality != null ? cardinality : 1;
                String onClassPred = isDataRestriction ? "owl:onDataRange" : "owl:onClass";
                String cardPred = switch (restrictionType) {
                    case "min" -> "owl:minQualifiedCardinality";
                    case "max" -> "owl:maxQualifiedCardinality";
                    default -> "owl:qualifiedCardinality";
                };
                triples.append(bn).append(" ").append(cardPred)
                        .append(" \"").append(card).append("\"^^xsd:nonNegativeInteger .\n");
                triples.append(bn).append(" ").append(onClassPred)
                        .append(" <").append(fillerIri).append("> .\n");
            }
            default -> {
                return "";
            }
        }

        return "INSERT DATA {\n" + triples + "}";
    }

    /**
     * Confirm a restriction axiom is readable from GraphDB immediately after INSERT.
     * Throws if the triple pattern is missing (catches silent no-ops from bad SPARQL injection).
     */
    private void verifyRestrictionInserted(String projectId, String graphUri, MutationOp op,
                                           boolean isDataRestriction) {
        String classIri = op.iri();
        String propertyIri = op.property();
        String restrictionType = op.restrictionType();
        String fillerIri = op.target();
        String axiomType = op.axiomType();
        Integer cardinality = op.cardinality();
        if (classIri == null || propertyIri == null || restrictionType == null || fillerIri == null) {
            return;
        }
        String axiomPredicate = "rdfs:subClassOf";
        if (axiomType != null) {
            axiomPredicate = switch (axiomType) {
                case "EquivalentTo" -> "owl:equivalentClass";
                case "DisjointWith" -> "owl:disjointWith";
                default -> "rdfs:subClassOf";
            };
        }
        String onFillerPred = isDataRestriction ? "owl:onDataRange" : "owl:onClass";
        String restrictionPattern = switch (restrictionType) {
            case "some" -> "?r owl:someValuesFrom <" + fillerIri + "> .";
            case "only" -> "?r owl:allValuesFrom <" + fillerIri + "> .";
            case "value" -> "?r owl:hasValue <" + fillerIri + "> .";
            case "min" -> {
                int card = cardinality != null ? cardinality : 1;
                yield "?r owl:minQualifiedCardinality \"" + card + "\"^^xsd:nonNegativeInteger .\n"
                        + "?r " + onFillerPred + " <" + fillerIri + "> .";
            }
            case "max" -> {
                int card = cardinality != null ? cardinality : 1;
                yield "?r owl:maxQualifiedCardinality \"" + card + "\"^^xsd:nonNegativeInteger .\n"
                        + "?r " + onFillerPred + " <" + fillerIri + "> .";
            }
            case "exactly" -> {
                int card = cardinality != null ? cardinality : 1;
                yield "?r owl:qualifiedCardinality \"" + card + "\"^^xsd:nonNegativeInteger .\n"
                        + "?r " + onFillerPred + " <" + fillerIri + "> .";
            }
            default -> null;
        };
        if (restrictionPattern == null) {
            return;
        }
        // Use GRAPH <uri> inline so the check targets the exact named graph regardless
        // of how the connection resolves its default graph — more reliable than FROM injection.
        String verifyQuery = PREFIXES + """
            ASK WHERE {
              GRAPH <%s> {
                <%s> %s ?r .
                ?r owl:onProperty <%s> .
                %s
              }
            }
            """.formatted(graphUri, classIri, axiomPredicate, propertyIri, restrictionPattern);
        log.info("[MUTATION] Verifying restriction insertion in graph {}: {}", graphUri, verifyQuery);
        boolean found = datasetService.execAskInGraph(projectId, graphUri, verifyQuery);
        if (!found) {
            log.error("[MUTATION] Restriction verification FAILED for class={} property={} type={} filler={}",
                    classIri, propertyIri, restrictionType, fillerIri);
            throw new RuntimeException(
                    "Restriction was not persisted to the ontology graph (verification failed)");
        }
        log.info("[MUTATION] Restriction verification OK for class={}", classIri);
    }
    
    /**
     * Build SPARQL DELETE to remove an OWL restriction
     * This is more complex because we need to find and delete the blank node
     */
    private String buildDeleteRestrictionSparql(MutationOp op, boolean isDataRestriction) {
        String classIri = op.iri();
        String propertyIri = op.property();
        String restrictionType = op.restrictionType();
        String fillerIri = op.target(); // NOW WE USE THIS to match the exact restriction!
        String axiomType = op.axiomType();
        Integer cardinality = op.cardinality(); // This is an Integer, not a String!
        
        log.info("[MUTATION] buildDeleteRestrictionSparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   propertyIri: {}", propertyIri);
        log.info("[MUTATION]   restrictionType: {}", restrictionType);
        log.info("[MUTATION]   fillerIri: {}", fillerIri);
        log.info("[MUTATION]   cardinality: {}", cardinality);
        log.info("[MUTATION]   axiomType: {}", axiomType);
        log.info("[MUTATION]   isDataRestriction: {}", isDataRestriction);
        
        // Validate required fields
        if (classIri == null || propertyIri == null || restrictionType == null || fillerIri == null) {
            log.error("[MUTATION] Missing required fields for delete restriction");
            throw new IllegalArgumentException("Missing required fields for delete restriction");
        }
        
        // Determine the axiom predicate
        String axiomPredicate = switch (axiomType != null ? axiomType : "SubClassOf") {
            case "EquivalentTo" -> "owl:equivalentClass";
            case "DisjointWith" -> "owl:disjointWith";
            default -> "rdfs:subClassOf";
        };

        String onFillerPred = isDataRestriction ? "owl:onDataRange" : "owl:onClass";
        String restrictionPattern = switch (restrictionType) {
            case "some" -> "?restriction owl:someValuesFrom <" + fillerIri + "> .";
            case "only" -> "?restriction owl:allValuesFrom <" + fillerIri + "> .";
            case "value" -> "?restriction owl:hasValue <" + fillerIri + "> .";
            case "min", "max", "exactly" -> {
                int card = cardinality != null ? cardinality : 1;
                String cardPred = switch (restrictionType) {
                    case "min" -> "owl:minQualifiedCardinality";
                    case "max" -> "owl:maxQualifiedCardinality";
                    default -> "owl:qualifiedCardinality";
                };
                yield "?restriction " + cardPred + " \"" + card + "\"^^xsd:nonNegativeInteger .\n"
                        + "              ?restriction " + onFillerPred + " <" + fillerIri + "> .";
            }
            default -> {
                log.warn("[MUTATION] Unknown restriction type: {}", restrictionType);
                yield "";
            }
        };

        if (restrictionPattern.isEmpty()) {
            throw new IllegalArgumentException("Unknown restriction type: " + restrictionType);
        }

        // Match ONLY the restriction linked via the correct axiom predicate (SubClassOf vs EquivalentTo).
        String sparql = """
            DELETE {
              <%s> %s ?restriction .
              ?restriction ?p ?o .
            }
            WHERE {
              <%s> %s ?restriction .
              ?restriction a owl:Restriction .
              ?restriction owl:onProperty <%s> .
              %s
              ?restriction ?p ?o .
            }
            """.formatted(classIri, axiomPredicate, classIri, axiomPredicate, propertyIri, restrictionPattern);
        
        log.info("[MUTATION] Generated delete restriction SPARQL:");
        log.info("[MUTATION] {}", sparql);
        
        return sparql;
    }
    
    /**
     * Build SPARQL INSERT to add an owl:disjointUnionOf axiom
     * This creates an RDF list for the member classes
     */
    private String buildDisjointUnionSparql(String classIri, String[] memberIris) {
        log.info("[MUTATION] buildDisjointUnionSparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   memberIris: {}", String.join(", ", memberIris));
        
        // Trim all member IRIs to remove any whitespace
        for (int i = 0; i < memberIris.length; i++) {
            memberIris[i] = memberIris[i].trim();
        }
        
        // Build an RDF list using blank nodes
        // Format: _:b1 rdf:first <member1>; rdf:rest _:b2. _:b2 rdf:first <member2>; rdf:rest rdf:nil.
        StringBuilder insertBuilder = new StringBuilder();
        insertBuilder.append("INSERT DATA {\n");
        insertBuilder.append("  <").append(classIri).append("> owl:disjointUnionOf _:list0 .\n");
        
        for (int i = 0; i < memberIris.length; i++) {
            String currentList = "_:list" + i;
            String nextList = (i == memberIris.length - 1) ? "rdf:nil" : "_:list" + (i + 1);
            
            // Each blank node definition must be complete before the period
            insertBuilder.append("  ").append(currentList)
                .append(" rdf:first <").append(memberIris[i]).append("> ;\n")
                .append("             rdf:rest ").append(nextList).append(" .\n");
        }
        
        insertBuilder.append("}\n");
        
        String sparql = insertBuilder.toString();
        log.info("[MUTATION]   Generated disjoint union SPARQL:");
        log.info("[MUTATION]   {}", sparql);
        return sparql;
    }
    
    /**
     * Build SPARQL DELETE to remove an owl:disjointUnionOf axiom and its RDF list
     */
    private String buildDeleteDisjointUnionSparql(String classIri, String listNodeId) {
        log.info("[MUTATION] buildDeleteDisjointUnionSparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   listNodeId: {}", listNodeId);
        
        // Delete the disjoint union axiom and all list nodes
        // This is complex because we need to traverse and delete the entire RDF list
        String sparql = """
            DELETE {
              <%s> owl:disjointUnionOf ?list .
              ?node rdf:first ?first .
              ?node rdf:rest ?rest .
            }
            WHERE {
              <%s> owl:disjointUnionOf ?list .
              ?list rdf:rest* ?node .
              ?node rdf:first ?first .
              ?node rdf:rest ?rest .
            }
            """.formatted(classIri, classIri);
        
        log.info("[MUTATION]   Generated delete disjoint union SPARQL: {}", sparql);
        return sparql;
    }
    
    /**
     * Build SPARQL INSERT to add an owl:hasKey axiom
     * This creates an RDF list for the key properties
     */
    private String buildHasKeySparql(String classIri, String[] propertyIris) {
        log.info("[MUTATION] buildHasKeySparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   propertyIris: {}", String.join(", ", propertyIris));
        
        // Build an RDF list with a named IRI as the list head so it can be precisely deleted later
        String listHeadIri = "http://ontocode.org/haskey/" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder insertBuilder = new StringBuilder();
        insertBuilder.append("INSERT DATA {\n");
        insertBuilder.append("  <").append(classIri).append("> owl:hasKey <").append(listHeadIri).append("> .\n");

        for (int i = 0; i < propertyIris.length; i++) {
            String currentNode = (i == 0) ? "<" + listHeadIri + ">" : "_:keyNode" + i;
            String nextNode = (i == propertyIris.length - 1) ? "rdf:nil" : "_:keyNode" + (i + 1);
            insertBuilder.append("  ").append(currentNode)
                .append(" rdf:first <").append(propertyIris[i].trim()).append("> ;\n")
                .append("               rdf:rest ").append(nextNode).append(" .\n");
        }
        
        insertBuilder.append("}\n");
        
        String sparql = insertBuilder.toString();
        log.info("[MUTATION]   Generated has key SPARQL: {}", sparql);
        return sparql;
    }
    
    /**
     * Build SPARQL DELETE to remove an owl:hasKey axiom and its RDF list
     */
    private String buildDeleteHasKeySparql(String classIri, String listNodeId) {
        log.info("[MUTATION] buildDeleteHasKeySparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   listNodeId: {}", listNodeId);
        
        String sparql;
        if (listNodeId != null && (listNodeId.startsWith("http://") || listNodeId.startsWith("https://"))) {
            // Named IRI list head (new data format) — delete precisely by IRI
            sparql = """
                DELETE {
                  <%s> owl:hasKey <%s> .
                  ?node rdf:first ?first .
                  ?node rdf:rest ?rest .
                }
                WHERE {
                  <%s> owl:hasKey <%s> .
                  <%s> rdf:rest* ?node .
                  ?node rdf:first ?first .
                  ?node rdf:rest ?rest .
                }
                """.formatted(classIri, listNodeId, classIri, listNodeId, listNodeId);
        } else {
            // Blank node list head (legacy data) — filter by internal ID via STR()
            // STR(?bnode) behaviour is implementation-specific; in GraphDB it returns the blank node identifier.
            // Worst case this is a no-op (safer than deleting all has-key axioms).
            String propsCsv = listNodeId != null ? listNodeId.replace("hasKey_props_", "").replace("\"", "\\\"") : "";
            String sortedPropsCsv = java.util.Arrays.stream(propsCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            sparql = """
                DELETE {
                  <%s> owl:hasKey ?list .
                  ?node rdf:first ?first .
                  ?node rdf:rest ?rest .
                }
                WHERE {
                  {
                    SELECT ?list (GROUP_CONCAT(?prop; separator=",") AS ?propsConcat)
                    WHERE {
                      SELECT ?list ?prop
                      WHERE {
                        <%s> owl:hasKey ?list .
                        ?list rdf:rest* ?n .
                        ?n rdf:first ?prop .
                      }
                      ORDER BY ?prop
                    }
                    GROUP BY ?list
                  }
                  FILTER(?propsConcat = "%s")
                  ?list rdf:rest* ?node .
                  ?node rdf:first ?first .
                  ?node rdf:rest ?rest .
                }
                """.formatted(classIri, classIri, sortedPropsCsv);
        }

        log.info("[MUTATION]   Generated delete has key SPARQL: {}", sparql);
        return sparql;
    }
    
    /**
     * Build SPARQL INSERT to add an owl:propertyChainAxiom (RDF list of property IRIs).
     * Chain expression format: "iri1 o iri2 [o iri3 ...]"
     */
    private String buildPropertyChainSparql(String propertyIri, String[] chainPropertyIris) {
        log.info("[MUTATION] buildPropertyChainSparql: property={}, chain={}", propertyIri, String.join(" o ", chainPropertyIris));
        StringBuilder sb = new StringBuilder("INSERT DATA {\n");
        sb.append("  <").append(propertyIri).append("> owl:propertyChainAxiom _:chain0 .\n");
        for (int i = 0; i < chainPropertyIris.length; i++) {
            String cur = "_:chain" + i;
            String next = (i == chainPropertyIris.length - 1) ? "rdf:nil" : "_:chain" + (i + 1);
            sb.append("  ").append(cur).append(" rdf:first <").append(chainPropertyIris[i].trim()).append("> ;\n");
            sb.append("             rdf:rest ").append(next).append(" .\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Build SPARQL DELETE to remove a specific owl:propertyChainAxiom whose members
     * match the given ordered sequence.
     */
    private String buildDeletePropertyChainSparql(String propertyIri, String[] chainPropertyIris) {
        log.info("[MUTATION] buildDeletePropertyChainSparql: property={}, chain={}", propertyIri, String.join(" o ", chainPropertyIris));
        int n = chainPropertyIris.length;
        StringBuilder where = new StringBuilder();
        where.append("  <").append(propertyIri).append("> owl:propertyChainAxiom ?head .\n");
        // Match exact sequence to identify the right chain list
        for (int i = 0; i < n; i++) {
            String nodeVar = (i == 0) ? "?head" : "?cn" + i;
            String nextVar = (i == n - 1) ? "rdf:nil" : "?cn" + (i + 1);
            where.append("  ").append(nodeVar).append(" rdf:first <").append(chainPropertyIris[i].trim()).append("> .\n");
            where.append("  ").append(nodeVar).append(" rdf:rest ").append(nextVar).append(" .\n");
        }
        // Collect all list nodes for deletion
        where.append("  ?head rdf:rest* ?delNode .\n");
        where.append("  ?delNode rdf:first ?delFirst .\n");
        where.append("  ?delNode rdf:rest ?delRest .\n");
        return "DELETE {\n"
            + "  <" + propertyIri + "> owl:propertyChainAxiom ?head .\n"
            + "  ?delNode rdf:first ?delFirst .\n"
            + "  ?delNode rdf:rest ?delRest .\n"
            + "}\nWHERE {\n"
            + where.toString()
            + "}";
    }

    /**
     * Build SPARQL INSERT to add an owl:intersectionOf class expression
     * Format: :Class rdfs:subClassOf/:equivalentClass [ owl:intersectionOf (:A :B :C) ]
     */
    private String buildIntersectionSparql(String classIri, String[] memberIris, String axiomType) {
        log.info("[MUTATION] buildIntersectionSparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   memberIris: {}", String.join(", ", memberIris));
        log.info("[MUTATION]   axiomType: {}", axiomType);
        
        String axiomPredicate = getAxiomPredicate(axiomType);
        
        // Build an RDF list for the intersection members
        StringBuilder insertBuilder = new StringBuilder();
        insertBuilder.append("INSERT DATA {\n");
        insertBuilder.append("  <").append(classIri).append("> ").append(axiomPredicate).append(" _:intersection .\n");
        insertBuilder.append("  _:intersection owl:intersectionOf _:list0 .\n");
        
        for (int i = 0; i < memberIris.length; i++) {
            String currentList = "_:list" + i;
            String nextList = (i == memberIris.length - 1) ? "rdf:nil" : "_:list" + (i + 1);
            insertBuilder.append("  ").append(currentList)
                .append(" rdf:first <").append(memberIris[i].trim()).append("> ;\n");
            insertBuilder.append("             rdf:rest ").append(nextList).append(" .\n");
        }
        
        insertBuilder.append("}\n");
        
        String sparql = insertBuilder.toString();
        log.info("[MUTATION]   Generated intersection SPARQL: {}", sparql);
        return sparql;
    }
    
    /**
     * Build SPARQL INSERT to add an owl:unionOf class expression
     * Format: :Class rdfs:subClassOf/:equivalentClass [ owl:unionOf (:A :B :C) ]
     */
    private String buildUnionSparql(String classIri, String[] memberIris, String axiomType) {
        log.info("[MUTATION] buildUnionSparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   memberIris: {}", String.join(", ", memberIris));
        log.info("[MUTATION]   axiomType: {}", axiomType);
        
        String axiomPredicate = getAxiomPredicate(axiomType);
        
        // Build an RDF list for the union members
        StringBuilder insertBuilder = new StringBuilder();
        insertBuilder.append("INSERT DATA {\n");
        insertBuilder.append("  <").append(classIri).append("> ").append(axiomPredicate).append(" _:union .\n");
        insertBuilder.append("  _:union owl:unionOf _:list0 .\n");
        
        for (int i = 0; i < memberIris.length; i++) {
            String currentList = "_:list" + i;
            String nextList = (i == memberIris.length - 1) ? "rdf:nil" : "_:list" + (i + 1);
            insertBuilder.append("  ").append(currentList)
                .append(" rdf:first <").append(memberIris[i].trim()).append("> ;\n");
            insertBuilder.append("             rdf:rest ").append(nextList).append(" .\n");
        }
        
        insertBuilder.append("}\n");

        String sparql = insertBuilder.toString();
        log.info("[MUTATION]   Generated union SPARQL: {}", sparql);
        return sparql;
    }

    /**
     * Build SPARQL for a General Class Axiom (GCA) where the subject is an anonymous intersection.
     * Produces: (A and B) rdfs:subClassOf <classIri>
     */
    private String buildGCAIntersectionSparql(String classIri, String[] memberIris) {
        log.info("[MUTATION] buildGCAIntersectionSparql: classIri={}, members={}", classIri, String.join(", ", memberIris));
        StringBuilder sb = new StringBuilder("INSERT DATA {\n");
        sb.append("  _:gcaIntersection owl:intersectionOf _:gcaList0 .\n");
        sb.append("  _:gcaIntersection rdfs:subClassOf <").append(classIri).append("> .\n");
        for (int i = 0; i < memberIris.length; i++) {
            String cur = "_:gcaList" + i;
            String next = (i == memberIris.length - 1) ? "rdf:nil" : "_:gcaList" + (i + 1);
            sb.append("  ").append(cur).append(" rdf:first <").append(memberIris[i].trim()).append("> ;\n");
            sb.append("             rdf:rest ").append(next).append(" .\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Build SPARQL for a General Class Axiom (GCA) where the subject is an anonymous union.
     * Produces: (A or B) rdfs:subClassOf <classIri>
     */
    private String buildGCAUnionSparql(String classIri, String[] memberIris) {
        log.info("[MUTATION] buildGCAUnionSparql: classIri={}, members={}", classIri, String.join(", ", memberIris));
        StringBuilder sb = new StringBuilder("INSERT DATA {\n");
        sb.append("  _:gcaUnion owl:unionOf _:gcaList0 .\n");
        sb.append("  _:gcaUnion rdfs:subClassOf <").append(classIri).append("> .\n");
        for (int i = 0; i < memberIris.length; i++) {
            String cur = "_:gcaList" + i;
            String next = (i == memberIris.length - 1) ? "rdf:nil" : "_:gcaList" + (i + 1);
            sb.append("  ").append(cur).append(" rdf:first <").append(memberIris[i].trim()).append("> ;\n");
            sb.append("             rdf:rest ").append(next).append(" .\n");
        }
        sb.append("}\n");
        return sb.toString();
    }


    /**
     * Build SPARQL INSERT to add an owl:complementOf class expression
     * Format: :Class rdfs:subClassOf/:equivalentClass [ owl:complementOf :A ]
     */
    private String buildComplementSparql(String classIri, String complementIri, String axiomType) {
        log.info("[MUTATION] buildComplementSparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   complementIri: {}", complementIri);
        log.info("[MUTATION]   axiomType: {}", axiomType);
        
        String axiomPredicate = getAxiomPredicate(axiomType);
        
        String sparql = """
            INSERT DATA {
              <%s> %s [
                owl:complementOf <%s>
              ] .
            }
            """.formatted(classIri, axiomPredicate, complementIri);
        
        log.info("[MUTATION]   Generated complement SPARQL: {}", sparql);
        return sparql;
    }
    
    /**
     * Build SPARQL INSERT to add an owl:oneOf class expression (enumeration)
     * Format: :Class owl:equivalentClass [ owl:oneOf (:ind1 :ind2 :ind3) ]
     */
    private String buildOneOfSparql(String classIri, String[] individualIris, String axiomType) {
        log.info("[MUTATION] buildOneOfSparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   individualIris: {}", String.join(", ", individualIris));
        log.info("[MUTATION]   axiomType: {}", axiomType);
        
        String axiomPredicate = getAxiomPredicate(axiomType != null ? axiomType : "EquivalentTo");
        
        // Build an RDF list for the oneOf individuals
        StringBuilder insertBuilder = new StringBuilder();
        insertBuilder.append("INSERT DATA {\n");
        insertBuilder.append("  <").append(classIri).append("> ").append(axiomPredicate).append(" _:oneOf .\n");
        insertBuilder.append("  _:oneOf owl:oneOf _:list0 .\n");
        
        for (int i = 0; i < individualIris.length; i++) {
            String currentList = "_:list" + i;
            String nextList = (i == individualIris.length - 1) ? "rdf:nil" : "_:list" + (i + 1);
            insertBuilder.append("  ").append(currentList)
                .append(" rdf:first <").append(individualIris[i].trim()).append("> ;\n");
            insertBuilder.append("             rdf:rest ").append(nextList).append(" .\n");
        }
        
        insertBuilder.append("}\n");
        
        String sparql = insertBuilder.toString();
        log.info("[MUTATION]   Generated oneOf SPARQL: {}", sparql);
        return sparql;
    }
    
    /**
     * Delete a blank-node axiom (GCA, anonymous restriction, etc.) by its blank-node ID.
     * The ID is the STR() representation returned from SPARQL queries.
     */
    private static final Pattern DATATYPE_RESTRICTION_EXPR = Pattern.compile("^(.+?)\\[(.+)]$");
    private static final Pattern FACET_PATTERN = Pattern.compile("(>=|<=|>|<|=)\\s*(.+)");

    private String resolvePrefixedIri(String prefixed) {
        if (prefixed == null) return null;
        String trimmed = prefixed.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("urn:")) {
            return trimmed;
        }
        int colon = trimmed.indexOf(':');
        if (colon <= 0) return trimmed;
        String prefix = trimmed.substring(0, colon);
        String local = trimmed.substring(colon + 1);
        return switch (prefix) {
            case "xsd" -> "http://www.w3.org/2001/XMLSchema#" + local;
            case "rdf" -> "http://www.w3.org/1999/02/22-rdf-syntax-ns#" + local;
            case "rdfs" -> "http://www.w3.org/2000/01/rdf-schema#" + local;
            case "owl" -> "http://www.w3.org/2002/07/owl#" + local;
            default -> trimmed;
        };
    }

    private record DatatypeFacet(String predicate, String literalValue, String literalDatatype) {}

    private String buildDatatypeDefinitionSparql(String datatypeIri, String expression) {
        if (datatypeIri == null || datatypeIri.isBlank() || expression == null || expression.isBlank()) {
            return "";
        }
        String trimmed = expression.trim();
        if (!trimmed.contains("[")) {
            String baseIri = resolvePrefixedIri(trimmed);
            return PREFIXES + "INSERT DATA {\n"
                + "<" + datatypeIri + "> a rdfs:Datatype ;\n"
                + "  owl:equivalentClass <" + baseIri + "> .\n"
                + "}";
        }
        return buildDatatypeRestrictionSparql(datatypeIri, trimmed, "owl:equivalentClass");
    }

    private String buildDeleteDatatypeDefinitionSparql(String datatypeIri) {
        if (datatypeIri == null || datatypeIri.isBlank()) return "";
        return PREFIXES + """
            DELETE {
              <%s> owl:equivalentClass ?dr .
              ?dr ?p ?o .
              ?list ?lp ?lo .
              ?facet ?fp ?fo .
            }
            WHERE {
              <%s> owl:equivalentClass ?dr .
              OPTIONAL { ?dr ?p ?o . FILTER(?p != owl:equivalentClass) }
              OPTIONAL {
                ?dr owl:withRestrictions ?list .
                ?list (rdf:rest*)/rdf:first ?facet .
                ?facet ?fp ?fo .
                ?list ?lp ?lo .
              }
            }
            """.formatted(datatypeIri, datatypeIri);
    }

    private String buildDatatypeRestrictionSparql(String subjectIri, String expression, String predicate) {
        Matcher matcher = DATATYPE_RESTRICTION_EXPR.matcher(expression.trim());
        if (!matcher.matches()) {
            return "";
        }
        String datatypeIri = resolvePrefixedIri(matcher.group(1).trim());
        String facetsRaw = matcher.group(2).trim();
        List<DatatypeFacet> facets = parseDatatypeFacets(facetsRaw, datatypeIri);

        StringBuilder insert = new StringBuilder();
        insert.append(PREFIXES).append("INSERT DATA {\n");
        insert.append("  <").append(subjectIri).append("> ").append(predicate).append(" _:dr .\n");
        insert.append("  _:dr a rdfs:Datatype ;\n");
        insert.append("       owl:onDatatype <").append(datatypeIri).append(">");

        if (facets.isEmpty()) {
            insert.append(" .\n}\n");
            return insert.toString();
        }

        insert.append(" ;\n       owl:withRestrictions _:list0 .\n");
        for (int i = 0; i < facets.size(); i++) {
            DatatypeFacet facet = facets.get(i);
            String facetNode = "_:facet" + i;
            String currentList = "_:list" + i;
            String nextList = (i == facets.size() - 1) ? "rdf:nil" : "_:list" + (i + 1);
            insert.append("  ").append(currentList).append(" rdf:first ").append(facetNode).append(" ;\n");
            insert.append("             rdf:rest ").append(nextList).append(" .\n");
            insert.append("  ").append(facetNode).append(" <").append(facet.predicate()).append("> ");
            insert.append("\"").append(escapeSparqlString(facet.literalValue())).append("\"");
            if (facet.literalDatatype() != null) {
                insert.append("^^<").append(facet.literalDatatype()).append(">");
            }
            insert.append(" .\n");
        }
        insert.append("}\n");
        return insert.toString();
    }

    private List<DatatypeFacet> parseDatatypeFacets(String facetsRaw, String baseDatatypeIri) {
        List<DatatypeFacet> facets = new ArrayList<>();
        for (String part : facetsRaw.split(",")) {
            String facetText = part.trim();
            if (facetText.isEmpty()) continue;
            Matcher m = FACET_PATTERN.matcher(facetText);
            if (!m.matches()) continue;
            String op = m.group(1);
            String value = m.group(2).trim();
            String predicate = switch (op) {
                case ">=" -> "http://www.w3.org/2001/XMLSchema#minInclusive";
                case ">" -> "http://www.w3.org/2001/XMLSchema#minExclusive";
                case "<=" -> "http://www.w3.org/2001/XMLSchema#maxInclusive";
                case "<" -> "http://www.w3.org/2001/XMLSchema#maxExclusive";
                case "=" -> "http://www.w3.org/2001/XMLSchema#minInclusive";
                default -> null;
            };
            if (predicate == null) continue;
            facets.add(new DatatypeFacet(predicate, stripQuotes(value), baseDatatypeIri));
            if ("=".equals(op)) {
                facets.add(new DatatypeFacet(
                        "http://www.w3.org/2001/XMLSchema#maxInclusive",
                        stripQuotes(value),
                        baseDatatypeIri));
            }
        }
        return facets;
    }

    private String stripQuotes(String value) {
        if (value == null) return "";
        String v = value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private String escapeSparqlString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String buildDeleteDatatypeRestrictionSparql(String propertyIri, String predicate) {
        return PREFIXES + """
            DELETE {
              <%s> %s ?dr .
              ?dr ?p ?o .
              ?list ?lp ?lo .
              ?facet ?fp ?fo .
            }
            WHERE {
              <%s> %s ?dr .
              ?dr a rdfs:Datatype ; owl:onDatatype ?dt .
              OPTIONAL { ?dr ?p ?o . FILTER(?p NOT IN (rdf:type, owl:onDatatype, owl:withRestrictions)) }
              OPTIONAL {
                ?dr owl:withRestrictions ?list .
                ?list (rdf:rest*)/rdf:first ?facet .
                ?facet ?fp ?fo .
                ?list ?lp ?lo .
              }
            }
            """.formatted(propertyIri, predicate, propertyIri, predicate);
    }

    /**
     * After deleting <iri>, remove any blank-node expression elsewhere in the ontology that used
     * it as a restriction filler (fillerPredicates, e.g. someValuesFrom/onProperty) or as a plain
     * unionOf/intersectionOf/propertyChain list member — otherwise the expression is left dangling
     * (still linked from its ancestor, missing the triple that named it). Walks up through nested
     * list/expression wrapper predicates to the root blank node, then deletes the ancestor's link
     * to that root plus every triple in the whole expression subtree.
     *
     * @param fillerPredicates SPARQL property-path alternation (e.g. "owl:someValuesFrom|owl:onClass")
     *                         matching predicates whose object being <iri> makes their subject a
     *                         restriction that references it.
     * @param anchorPredicates comma-separated predicate list (for a SPARQL IN(...) clause) that can
     *                         link a named ancestor to the root of such an expression.
     */
    private String buildDeleteDanglingExpressionsSparql(String iri, String fillerPredicates, String anchorPredicates) {
        return """
            DELETE {
              ?ancestor ?axiomPred ?root .
              ?node ?p ?o .
            }
            WHERE {
              {
                ?restr (%s) <%s> .
                ?root (owl:intersectionOf|owl:unionOf|owl:oneOf|rdf:first|rdf:rest)* ?restr .
              } UNION {
                ?listNode rdf:first <%s> .
                ?root (owl:intersectionOf|owl:unionOf|owl:oneOf|rdf:first|rdf:rest)* ?listNode .
              }
              ?ancestor ?axiomPred ?root .
              FILTER(?axiomPred IN (%s))
              FILTER(isIRI(?ancestor))
              # Deliberately excludes rdf:first from this path: rdf:first's object is a list MEMBER
              # (e.g. a named class like HamTopping in a unionOf), not part of the expression's own
              # scaffolding. Walking into it would delete that unrelated member's own declaration —
              # only tear down the wrapper/list-cell scaffold itself (isBlank(?node) as a hard guard).
              ?root (owl:intersectionOf|owl:unionOf|owl:oneOf|rdf:rest)* ?node .
              FILTER(isBlank(?node))
              ?node ?p ?o .
            }
            """.formatted(fillerPredicates, iri, iri, anchorPredicates);
    }

    private static final String CLASS_EXPR_FILLER_PREDICATES = "owl:someValuesFrom|owl:allValuesFrom|owl:hasValue|owl:onClass";
    private static final String CLASS_EXPR_ANCHOR_PREDICATES = "rdfs:subClassOf, owl:equivalentClass, owl:disjointWith, rdfs:domain, rdfs:range";
    private static final String PROPERTY_EXPR_ANCHOR_PREDICATES = "rdfs:subClassOf, owl:equivalentClass, owl:disjointWith, owl:propertyChainAxiom";
    private static final String DATATYPE_EXPR_ANCHOR_PREDICATES = "rdfs:subClassOf, owl:equivalentClass, rdfs:range";

    private String buildDeleteBlankNodeAxiomSparql(String blankNodeId, String ancestorIri) {
        if (blankNodeId == null || blankNodeId.isBlank()) {
            log.warn("[MUTATION] deleteAxiom requires a blank node ID");
            return "";
        }
        boolean hasAncestor = ancestorIri != null && !ancestorIri.isBlank();
        String anchorTriple = hasAncestor ? "  <" + ancestorIri + "> rdfs:subClassOf ?axiom .\n" : "";

        String matchFilter;
        if (hasAncestor) {
            // Blank node labels in a SPARQL result set are only guaranteed valid for that one
            // query execution (SPARQL protocol) — NOT stable across separate HTTP requests,
            // even against the same unchanged store. The id captured when classDetails listed
            // this axiom will almost never still match STR(?axiom) by the time a later, separate
            // delete request runs (this is why deleting an inherited anonymous ancestor axiom
            // was silently failing). Since we know which class asserts it, the anchor triple
            // above finds the blank node directly — no previously-observed label needed.
            // Trade-off: if that class has more than one distinct anonymous superclass
            // expression (rare), this deletes all of them, not just the one clicked.
            matchFilter = "  FILTER(isBlank(?axiom))\n";
        } else {
            // No ancestor to anchor on — fall back to matching by the previously-observed label.
            // RDF4J BNode.stringValue() returns the bare internal ID (e.g. "b0"), so SPARQL
            // STR(?bnode) also returns "b0" — not "_:b0". Strip the "_:" prefix.
            String rawId = blankNodeId.startsWith("_:") ? blankNodeId.substring(2) : blankNodeId;
            String escapedRawId = rawId.replace("\\", "\\\\").replace("\"", "\\\"");
            matchFilter = "  FILTER(isBlank(?axiom) && STR(?axiom) = \"" + escapedRawId + "\")\n";
        }

        String sparql = """
            DELETE {
            %s  ?axiom ?p ?o .
              ?listNode rdf:first ?first .
              ?listNode rdf:rest ?rest .
            }
            WHERE {
            %s%s  ?axiom ?p ?o .
              OPTIONAL {
                ?axiom (owl:intersectionOf|owl:unionOf|owl:oneOf|rdf:first|rdf:rest)* ?listNode .
                ?listNode rdf:first ?first .
                ?listNode rdf:rest ?rest .
              }
            }
            """.formatted(anchorTriple, anchorTriple, matchFilter);
        log.info("[MUTATION] deleteAxiom SPARQL for blank node {} (ancestorIri={}): {}", blankNodeId, ancestorIri, sparql);
        return sparql;
    }

    /**
     * Build SPARQL DELETE to remove a complex class expression (intersection, union, complement, oneOf)
     * This deletes the blank node and all its contents including RDF lists
     */
    private String buildDeleteComplexExpressionSparql(String classIri, String bnodeId, String axiomType) {
        log.info("[MUTATION] buildDeleteComplexExpressionSparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   bnodeId: {}", bnodeId);
        log.info("[MUTATION]   axiomType: {}", axiomType);
        
        String axiomPredicate = getAxiomPredicate(axiomType);
        
        // Delete the complex expression, its properties, and any RDF lists it contains
        String sparql = """
            DELETE {
              <%s> %s ?expr .
              ?expr ?exprProp ?exprValue .
              ?listNode rdf:first ?first .
              ?listNode rdf:rest ?rest .
            }
            WHERE {
              <%s> %s ?expr .
              ?expr ?exprProp ?exprValue .
              OPTIONAL {
                ?expr ?listProp ?list .
                ?list rdf:rest* ?listNode .
                ?listNode rdf:first ?first .
                ?listNode rdf:rest ?rest .
              }
            }
            """.formatted(classIri, axiomPredicate, classIri, axiomPredicate);
        
        log.info("[MUTATION]   Generated delete complex expression SPARQL: {}", sparql);
        return sparql;
    }
    
    /**
     * Helper method to get the axiom predicate based on axiom type
     */
    private boolean isComplexExpression(String expression) {
        if (expression == null) return false;
        return expression.contains(" ") && !expression.trim().startsWith("<") && !expression.trim().startsWith("_:");
    }

    private String resolveEntity(String projectId, String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        
        // If it's already an IRI or CURIE, return it
        if (trimmed.startsWith("http") || trimmed.startsWith("urn:") || trimmed.startsWith("_:")) return trimmed;
        if (trimmed.contains(":") && !trimmed.contains(" ")) return trimmed; // CURIE like owl:Thing
        
        // Try to find by label
        // Escape quotes in name
        String escapedName = trimmed.replace("\"", "\\\"");
        String query = PREFIXES + """
            SELECT ?iri WHERE {
                ?iri rdfs:label "%s" .
            } LIMIT 1
            """.formatted(escapedName);
            
        try {
            TupleQueryResult result = datasetService.execSelect(projectId, query);
            if (result.hasNext()) {
                BindingSet bs = result.next();
                return bs.getValue("iri").stringValue();
            }
        } catch (Exception e) {
            log.warn("Failed to resolve entity '{}': {}", trimmed, e.getMessage());
        }
        return trimmed; // Fallback
    }

    private String buildClassExpressionSparql(String projectId, String expression) {
        if (!isComplexExpression(expression)) {
             // Ensure it's wrapped in <> if it's a full IRI and not already wrapped
             String trimmed = expression.trim();
             if ((trimmed.startsWith("http") || trimmed.startsWith("urn:")) && !trimmed.startsWith("<")) {
                 return "<" + trimmed + ">";
             }
             return trimmed; // CURIE or already wrapped
        }

        // Simple parser for "P some C"
        // Regex: ^(\S+)\s+(some|only)\s+(.+)$
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("^(\\S+)\\s+(some|only)\\s+(.+)$");
        java.util.regex.Matcher m = p.matcher(expression);
        
        if (m.find()) {
            String property = m.group(1);
            String type = m.group(2);
            String target = m.group(3);
            
            String propertyIri = resolveEntity(projectId, property);
            String targetIri = resolveEntity(projectId, target);
            
            return buildRestrictionBody(propertyIri, type, targetIri, null, false);
        }
        
        throw new IllegalArgumentException("Unsupported complex expression: " + expression);
    }

    private String getAxiomPredicate(String axiomType) {
        if (axiomType == null) {
            return "rdfs:subClassOf";
        }
        return switch (axiomType) {
            case "EquivalentTo" -> "owl:equivalentClass";
            case "DisjointWith" -> "owl:disjointWith";
            default -> "rdfs:subClassOf";
        };
    }

    private void requireDraftCopyReady(String projectId, String userId) {
        if (draftCopyService == null || !draftCopyService.isReady(projectId, userId)) {
            throw new DraftNotReadyException();
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record MutationOp(
        String type,
        String iri,
        String label,
        String parent,
        String property,
        String value,
        String target,
        String classIri,
        // Additional fields for restriction support
        String restrictionType, // some, only, min, max, exactly, value
        Integer cardinality,    // For min, max, exactly restrictions
        String axiomType,       // EquivalentTo, SubClassOf
        String oldValue,        // For tracking the old value in updates
        String language,        // Language tag for annotation literals (e.g. "en", "fr")
        String datatype,        // Datatype IRI for annotation literals (e.g. xsd:boolean)
        String ancestorIri      // Subject class for anonymous ancestor deletes (rdfs:subClassOf subject)
    ) {}
}
