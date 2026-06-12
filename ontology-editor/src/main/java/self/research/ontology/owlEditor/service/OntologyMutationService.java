package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.BindingSet;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.controller.VisualizationController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class OntologyMutationService {

    private static final Logger log = LoggerFactory.getLogger(OntologyMutationService.class);

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
        """;

    private final GraphDBDatasetService datasetService;
    private final OntologyIndexService indexService;
    private final ProjectMetadataService metadataService;
    private final GraphGeneratingService graphGeneratingService;
    private final TopLevelClassCacheService topLevelCacheService;
    private final Executor metadataExecutor;

    @Autowired @Lazy
    private VisualizationController visualizationController;

    @Autowired(required = false) @Nullable
    private HierarchyIndexService hierarchyIndexService;

    @Autowired(required = false) @Nullable
    private ProjectOntologyCache ontologyCache;

    public OntologyMutationService(GraphDBDatasetService datasetService,
                                   OntologyIndexService indexService,
                                   ProjectMetadataService metadataService,
                                   GraphGeneratingService graphGeneratingService,
                                   TopLevelClassCacheService topLevelCacheService,
                                   @Qualifier("metadataExecutor") Executor metadataExecutor) {
        this.datasetService = datasetService;
        this.indexService = indexService;
        this.metadataService = metadataService;
        this.graphGeneratingService = graphGeneratingService;
        this.topLevelCacheService = topLevelCacheService;
        this.metadataExecutor = metadataExecutor;
    }

    /**
     * Apply ontology mutations. Spring cache eviction is centralized in
     * {@link GraphDBDatasetService#execUpdate} via {@link OntologySpringCacheEvictionService}.
     */
    public void apply(String projectId, List<MutationOp> ops) {
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
            log.warn("[MUTATION] No valid operations to apply after filtering");
            return;
        }
        
        log.info("[MUTATION] Generated SPARQL (BEFORE graph injection):");
        log.info("[MUTATION] {}", sparql);
        
        try {
            MutationContext.setOps(ops);
            long sparqlStart = System.currentTimeMillis();
            datasetService.execUpdate(projectId, sparql);
            long sparqlDuration = System.currentTimeMillis() - sparqlStart;
            log.info("[MUTATION] SPARQL update completed in {}ms for project={}", sparqlDuration, projectId);

            // OWLAPI patch/evict + Spring cache eviction handled in execUpdate → mutationCoordinator

            topLevelCacheService.evict(projectId);
            if (hierarchyIndexService != null) {
                hierarchyIndexService.markStale(projectId);
            }

            // Clear graph cache after mutations
            graphGeneratingService.clearGraphCache();
            if (visualizationController != null) {
                visualizationController.clearCache(projectId);
            }
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
     * Apply a pre-built SPARQL update for cases where the mutation payload needs
     * anonymous OWL class expressions generated by OWLAPI, such as DL Query
     * "Add to ontology".
     */
    public void applyRawUpdate(String projectId, String sparql) {
        if (sparql == null || sparql.isBlank()) {
            log.warn("[MUTATION] Empty raw SPARQL update for project={}", projectId);
            return;
        }

        datasetService.execUpdate(projectId, sparql);
        topLevelCacheService.evict(projectId);
        graphGeneratingService.clearGraphCache();
        if (visualizationController != null) {
            visualizationController.clearCache(projectId);
        }

        CompletableFuture.runAsync(() -> {
            Map<String, Object> meta = indexService.computeMetadata(projectId);
            metadataService.writeMeta(projectId, meta);
        }, metadataExecutor);
    }

    public void makeSiblingsDisjoint(String projectId, List<String> classIds) {
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
        
        datasetService.execUpdate(projectId, sparqlBuilder.toString());
        topLevelCacheService.evict(projectId);

        // Clear graph cache
        graphGeneratingService.clearGraphCache();
        if (visualizationController != null) {
            visualizationController.clearCache(projectId);
        }

        CompletableFuture.runAsync(() -> {
            Map<String, Object> meta = indexService.computeMetadata(projectId);
            metadataService.writeMeta(projectId, meta);
        }, metadataExecutor);
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
            return "DELETE { <" + op.iri() + "> ?p ?o } WHERE { <" + op.iri() + "> ?p ?o };\n"
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
            if (op.target().startsWith("_:")) {
                // For blank nodes, use DELETE/WHERE pattern
                return "DELETE { <" + op.iri() + "> rdfs:subClassOf ?target }\n"
                    + "WHERE { <" + op.iri() + "> rdfs:subClassOf ?target .\n"
                    + "  FILTER(isBlank(?target) && str(?target) = \"" + op.target() + "\") }";
            } else {
                // For named IRIs, use DELETE DATA
                return "DELETE DATA {\n"
                    + "<" + op.iri() + "> rdfs:subClassOf <" + op.target() + "> .\n"
                    + "}";
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
            // Handle both named IRIs and blank nodes (anonymous nodes starting with _:)
            if (op.target().startsWith("_:")) {
                // For blank nodes, use DELETE/WHERE pattern
                return "DELETE { <" + op.iri() + "> owl:equivalentClass ?target }\n"
                    + "WHERE { <" + op.iri() + "> owl:equivalentClass ?target .\n"
                    + "  FILTER(isBlank(?target) && str(?target) = \"" + op.target() + "\") }";
            } else {
                // For named IRIs, use DELETE DATA
                return "DELETE DATA {\n"
                    + "<" + op.iri() + "> owl:equivalentClass <" + op.target() + "> .\n"
                    + "}";
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
            if (op.target().startsWith("_:")) {
                // For blank nodes, use DELETE/WHERE pattern
                return "DELETE { <" + op.iri() + "> owl:disjointWith ?target }\n"
                    + "WHERE { <" + op.iri() + "> owl:disjointWith ?target .\n"
                    + "  FILTER(isBlank(?target) && str(?target) = \"" + op.target() + "\") }";
            } else {
                // For named IRIs, use DELETE DATA
                return "DELETE DATA {\n"
                    + "<" + op.iri() + "> owl:disjointWith <" + op.target() + "> .\n"
                    + "}";
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
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> a owl:AnnotationProperty .\n"
                + optionalLabel(op.iri(), op.label()) + "\n"
                + "}";
        } else if (type.equals("deleteObjectProperty")) {
            return "DELETE { <" + op.iri() + "> ?p ?o } WHERE { <" + op.iri() + "> ?p ?o };\n"
                + "DELETE { ?s <" + op.iri() + "> ?o } WHERE { ?s <" + op.iri() + "> ?o };\n"
                + "DELETE { ?s ?p <" + op.iri() + "> } WHERE { ?s ?p <" + op.iri() + "> }";
        } else if (type.equals("deleteDataProperty")) {
            return "DELETE { <" + op.iri() + "> ?p ?o } WHERE { <" + op.iri() + "> ?p ?o };\n"
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
                String restrictionBody = buildRestrictionBody(op.property(), op.restrictionType(), op.target(), op.cardinality(), isDataRestriction);
                if (restrictionBody.isEmpty()) return "";
                return "INSERT {\n"
                    + "<" + op.iri() + "> rdfs:domain " + restrictionBody + " .\n"
                    + "} WHERE {}";
            } else {
                return "INSERT DATA {\n"
                    + "<" + op.iri() + "> rdfs:domain <" + op.target() + "> .\n"
                    + "}";
            }
        } else if (type.equals("deletePropertyDomain")) {
            if (op.target() != null && op.target().contains("|||")) {
                return buildDeletePropertyRestrictionSparql(op.iri(), "rdfs:domain", op.target());
            }
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> rdfs:domain <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("addPropertyRange")) {
            if (op.restrictionType() != null) {
                // Range is a restriction
                boolean isDataRestriction = "DataRestriction".equals(op.axiomType());
                String restrictionBody = buildRestrictionBody(op.property(), op.restrictionType(), op.target(), op.cardinality(), isDataRestriction);
                if (restrictionBody.isEmpty()) return "";
                return "INSERT {\n"
                    + "<" + op.iri() + "> rdfs:range " + restrictionBody + " .\n"
                    + "} WHERE {}";
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
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> rdfs:range <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("addDatatypeDefinition")) {
            return buildDatatypeDefinitionSparql(op.iri(), op.value());
        } else if (type.equals("deleteDatatypeDefinition")) {
            return buildDeleteDatatypeDefinitionSparql(op.iri());
        } else if (type.equals("addSubPropertyOf")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> rdfs:subPropertyOf <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteSubPropertyOf")) {
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> rdfs:subPropertyOf <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("addInverseProperty")) {
            // owl:inverseOf is symmetric in OWL — insert both directions so both
            // properties show each other as inverse (matches Protégé behaviour).
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> owl:inverseOf <" + op.target() + "> .\n"
                + "<" + op.target() + "> owl:inverseOf <" + op.iri() + "> .\n"
                + "}";
        } else if (type.equals("deleteInverseProperty")) {
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> owl:inverseOf <" + op.target() + "> .\n"
                + "<" + op.target() + "> owl:inverseOf <" + op.iri() + "> .\n"
                + "}";
        } else if (type.equals("addDisjointProperty")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> owl:propertyDisjointWith <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteDisjointProperty")) {
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> owl:propertyDisjointWith <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("addEquivalentProperty")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> owl:equivalentProperty <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteEquivalentProperty")) {
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> owl:equivalentProperty <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("addCharacteristic")) {
            return "INSERT DATA {\n"
                + "<" + op.iri() + "> a <" + op.target() + "> .\n"
                + "}";
        } else if (type.equals("deleteCharacteristic")) {
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> a <" + op.target() + "> .\n"
                + "}";
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
            return "DELETE { <" + op.iri() + "> ?p ?o } WHERE { <" + op.iri() + "> ?p ?o };\n"
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
            return "DELETE DATA {\n"
                + "<" + op.iri() + "> a <" + op.classIri() + "> .\n"
                + "}";
        } else if (type.equals("deleteAxiom")) {
            return buildDeleteBlankNodeAxiomSparql(op.iri());
        } else {
            throw new IllegalArgumentException("Unsupported op " + op.type());
        }
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
        
        String restrictionBody = buildRestrictionBody(propertyIri, restrictionType, fillerIri, cardinality, isDataRestriction);
        
        if (restrictionBody.isEmpty()) {
            log.warn("[MUTATION] Unknown restriction type: {}", restrictionType);
            return "";
        }

        String sparql = """
            INSERT {
              <%s> %s %s .
            } WHERE { }
            """.formatted(classIri, axiomPredicate, restrictionBody);
            
        log.info("[MUTATION]   Generated restriction SPARQL: {}", sparql);
        return sparql;
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
        
        // Determine the filler predicate based on restriction type
        String fillerPredicate = switch (restrictionType) {
            case "some" -> "owl:someValuesFrom";
            case "only" -> "owl:allValuesFrom";
            case "value" -> "owl:hasValue";
            case "min" -> "owl:minQualifiedCardinality";
            case "max" -> "owl:maxQualifiedCardinality";
            case "exactly" -> "owl:qualifiedCardinality";
            default -> "";
        };
        
        if (fillerPredicate.isEmpty()) {
            log.warn("[MUTATION] Unknown restriction type: {}", restrictionType);
            throw new IllegalArgumentException("Unknown restriction type: " + restrictionType);
        }
        
        // IMPORTANT: We need to be very specific about which restriction to delete
        // The WHERE clause must match ONLY the restriction connected via the correct axiom predicate
        // This prevents accidentally deleting a similar restriction from a different axiom type
        // (e.g., deleting from SubClassOf should not affect EquivalentTo)
        // Also require rdf:type owl:Restriction to match the query pattern
        String sparql = """
            DELETE {
              <%s> %s ?restriction .
              ?restriction ?p ?o .
            }
            WHERE {
              <%s> %s ?restriction .
              ?restriction a owl:Restriction .
              ?restriction owl:onProperty <%s> .
              ?restriction %s <%s> .
              ?restriction ?p ?o .
              FILTER(isBlank(?restriction))
            }
            """.formatted(classIri, axiomPredicate, classIri, axiomPredicate, propertyIri, fillerPredicate, fillerIri);
        
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
        insertBuilder.append("INSERT {\n");
        insertBuilder.append("  <").append(classIri).append("> owl:disjointUnionOf _:list0 .\n");
        
        for (int i = 0; i < memberIris.length; i++) {
            String currentList = "_:list" + i;
            String nextList = (i == memberIris.length - 1) ? "rdf:nil" : "_:list" + (i + 1);
            
            // Each blank node definition must be complete before the period
            insertBuilder.append("  ").append(currentList)
                .append(" rdf:first <").append(memberIris[i]).append("> ;\n")
                .append("             rdf:rest ").append(nextList).append(" .\n");
        }
        
        insertBuilder.append("} WHERE { }");
        
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
        
        // Build an RDF list using blank nodes
        StringBuilder insertBuilder = new StringBuilder();
        insertBuilder.append("INSERT {\n");
        insertBuilder.append("  <").append(classIri).append("> owl:hasKey _:keyList0 .\n");
        
        for (int i = 0; i < propertyIris.length; i++) {
            String currentList = "_:keyList" + i;
            String nextList = (i == propertyIris.length - 1) ? "rdf:nil" : "_:keyList" + (i + 1);
            insertBuilder.append("  ").append(currentList)
                .append(" rdf:first <").append(propertyIris[i].trim()).append("> ;\n");
            insertBuilder.append("             rdf:rest ").append(nextList).append(" .\n");
        }
        
        insertBuilder.append("} WHERE { }");
        
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
        
        // Delete the has key axiom and all list nodes
        String sparql = """
            DELETE {
              <%s> owl:hasKey ?list .
              ?node rdf:first ?first .
              ?node rdf:rest ?rest .
            }
            WHERE {
              <%s> owl:hasKey ?list .
              ?list rdf:rest* ?node .
              ?node rdf:first ?first .
              ?node rdf:rest ?rest .
            }
            """.formatted(classIri, classIri);
        
        log.info("[MUTATION]   Generated delete has key SPARQL: {}", sparql);
        return sparql;
    }
    
    /**
     * Build SPARQL INSERT to add an owl:propertyChainAxiom (RDF list of property IRIs).
     * Chain expression format: "iri1 o iri2 [o iri3 ...]"
     */
    private String buildPropertyChainSparql(String propertyIri, String[] chainPropertyIris) {
        log.info("[MUTATION] buildPropertyChainSparql: property={}, chain={}", propertyIri, String.join(" o ", chainPropertyIris));
        StringBuilder sb = new StringBuilder("INSERT {\n");
        sb.append("  <").append(propertyIri).append("> owl:propertyChainAxiom _:chain0 .\n");
        for (int i = 0; i < chainPropertyIris.length; i++) {
            String cur = "_:chain" + i;
            String next = (i == chainPropertyIris.length - 1) ? "rdf:nil" : "_:chain" + (i + 1);
            sb.append("  ").append(cur).append(" rdf:first <").append(chainPropertyIris[i].trim()).append("> ;\n");
            sb.append("             rdf:rest ").append(next).append(" .\n");
        }
        sb.append("} WHERE { }");
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
        insertBuilder.append("INSERT {\n");
        insertBuilder.append("  <").append(classIri).append("> ").append(axiomPredicate).append(" _:intersection .\n");
        insertBuilder.append("  _:intersection owl:intersectionOf _:list0 .\n");
        
        for (int i = 0; i < memberIris.length; i++) {
            String currentList = "_:list" + i;
            String nextList = (i == memberIris.length - 1) ? "rdf:nil" : "_:list" + (i + 1);
            insertBuilder.append("  ").append(currentList)
                .append(" rdf:first <").append(memberIris[i].trim()).append("> ;\n");
            insertBuilder.append("             rdf:rest ").append(nextList).append(" .\n");
        }
        
        insertBuilder.append("} WHERE { }");
        
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
        insertBuilder.append("INSERT {\n");
        insertBuilder.append("  <").append(classIri).append("> ").append(axiomPredicate).append(" _:union .\n");
        insertBuilder.append("  _:union owl:unionOf _:list0 .\n");
        
        for (int i = 0; i < memberIris.length; i++) {
            String currentList = "_:list" + i;
            String nextList = (i == memberIris.length - 1) ? "rdf:nil" : "_:list" + (i + 1);
            insertBuilder.append("  ").append(currentList)
                .append(" rdf:first <").append(memberIris[i].trim()).append("> ;\n");
            insertBuilder.append("             rdf:rest ").append(nextList).append(" .\n");
        }
        
        insertBuilder.append("} WHERE { }");

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
        StringBuilder sb = new StringBuilder("INSERT {\n");
        sb.append("  _:gcaIntersection owl:intersectionOf _:gcaList0 .\n");
        sb.append("  _:gcaIntersection rdfs:subClassOf <").append(classIri).append("> .\n");
        for (int i = 0; i < memberIris.length; i++) {
            String cur = "_:gcaList" + i;
            String next = (i == memberIris.length - 1) ? "rdf:nil" : "_:gcaList" + (i + 1);
            sb.append("  ").append(cur).append(" rdf:first <").append(memberIris[i].trim()).append("> ;\n");
            sb.append("             rdf:rest ").append(next).append(" .\n");
        }
        sb.append("} WHERE { }");
        return sb.toString();
    }

    /**
     * Build SPARQL for a General Class Axiom (GCA) where the subject is an anonymous union.
     * Produces: (A or B) rdfs:subClassOf <classIri>
     */
    private String buildGCAUnionSparql(String classIri, String[] memberIris) {
        log.info("[MUTATION] buildGCAUnionSparql: classIri={}, members={}", classIri, String.join(", ", memberIris));
        StringBuilder sb = new StringBuilder("INSERT {\n");
        sb.append("  _:gcaUnion owl:unionOf _:gcaList0 .\n");
        sb.append("  _:gcaUnion rdfs:subClassOf <").append(classIri).append("> .\n");
        for (int i = 0; i < memberIris.length; i++) {
            String cur = "_:gcaList" + i;
            String next = (i == memberIris.length - 1) ? "rdf:nil" : "_:gcaList" + (i + 1);
            sb.append("  ").append(cur).append(" rdf:first <").append(memberIris[i].trim()).append("> ;\n");
            sb.append("             rdf:rest ").append(next).append(" .\n");
        }
        sb.append("} WHERE { }");
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
            INSERT {
              <%s> %s [
                owl:complementOf <%s>
              ] .
            } WHERE { }
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
        insertBuilder.append("INSERT {\n");
        insertBuilder.append("  <").append(classIri).append("> ").append(axiomPredicate).append(" _:oneOf .\n");
        insertBuilder.append("  _:oneOf owl:oneOf _:list0 .\n");
        
        for (int i = 0; i < individualIris.length; i++) {
            String currentList = "_:list" + i;
            String nextList = (i == individualIris.length - 1) ? "rdf:nil" : "_:list" + (i + 1);
            insertBuilder.append("  ").append(currentList)
                .append(" rdf:first <").append(individualIris[i].trim()).append("> ;\n");
            insertBuilder.append("             rdf:rest ").append(nextList).append(" .\n");
        }
        
        insertBuilder.append("} WHERE { }");
        
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
        insert.append(PREFIXES).append("INSERT {\n");
        insert.append("  <").append(subjectIri).append("> ").append(predicate).append(" _:dr .\n");
        insert.append("  _:dr a rdfs:Datatype ;\n");
        insert.append("       owl:onDatatype <").append(datatypeIri).append(">");

        if (facets.isEmpty()) {
            insert.append(" .\n} WHERE {}");
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
        insert.append("} WHERE {}");
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

    private String buildDeleteBlankNodeAxiomSparql(String blankNodeId) {
        if (blankNodeId == null || blankNodeId.isBlank()) {
            log.warn("[MUTATION] deleteAxiom requires a blank node ID");
            return "";
        }
        String escapedId = blankNodeId.replace("\\", "\\\\").replace("\"", "\\\"");
        String sparql = """
            DELETE {
              ?axiom ?p ?o .
              ?listNode rdf:first ?first .
              ?listNode rdf:rest ?rest .
            }
            WHERE {
              FILTER(isBlank(?axiom) && STR(?axiom) = "%s")
              ?axiom ?p ?o .
              OPTIONAL {
                ?axiom (owl:intersectionOf|owl:unionOf|owl:oneOf|rdf:first|rdf:rest)* ?listNode .
                ?listNode rdf:first ?first .
                ?listNode rdf:rest ?rest .
              }
            }
            """.formatted(escapedId);
        log.info("[MUTATION] deleteAxiom SPARQL for blank node {}: {}", blankNodeId, sparql);
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
        String datatype         // Datatype IRI for annotation literals (e.g. xsd:boolean)
    ) {}
}
