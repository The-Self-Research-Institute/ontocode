package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    private final Executor metadataExecutor;

    public OntologyMutationService(GraphDBDatasetService datasetService,
                                   OntologyIndexService indexService,
                                   ProjectMetadataService metadataService,
                                   @Qualifier("metadataExecutor") Executor metadataExecutor) {
        this.datasetService = datasetService;
        this.indexService = indexService;
        this.metadataService = metadataService;
        this.metadataExecutor = metadataExecutor;
    }

    public void apply(String projectId, List<MutationOp> ops) {
        if (ops == null || ops.isEmpty()) {
            log.warn("[MUTATION] No operations to apply for project: {}", projectId);
            return;
        }

        log.info("[MUTATION] ========== APPLYING {} MUTATIONS ==========", ops.size());
        log.info("[MUTATION] Project: {}", projectId);
        
        String sparql = PREFIXES + "\n" + ops.stream()
                .map(this::toUpdate)
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
            datasetService.execUpdate(projectId, sparql);
            log.info("[MUTATION] ✅ Mutations applied successfully!");
            
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

    public void makeSiblingsDisjoint(String projectId, List<String> classIds) {
        if (classIds == null || classIds.size() < 2) {
            return;
        }

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

        CompletableFuture.runAsync(() -> {
            Map<String, Object> meta = indexService.computeMetadata(projectId);
            metadataService.writeMeta(projectId, meta);
        }, metadataExecutor);
    }

    private String toUpdate(MutationOp op) {
        // Validate IRI is not null for operations that require it
        if (op.iri() == null || op.iri().isBlank() || "null".equals(op.iri())) {
            log.error("[MUTATION] Invalid IRI for operation {}: iri={}", op.type(), op.iri());
            throw new IllegalArgumentException("IRI cannot be null or empty for operation: " + op.type());
        }
        
        return switch (op.type()) {
            case "createClass" -> """
                INSERT DATA {
                  <%s> a owl:Class .
                  %s
                  <%s> rdfs:subClassOf <%s> .
                }
                """.formatted(op.iri(), optionalLabel(op.iri(), op.label()), op.iri(), op.parent());
            case "updateClassLabel" -> """
                DELETE { <%s> rdfs:label ?o }
                INSERT { <%s> rdfs:label %s }
                WHERE  { OPTIONAL { <%s> rdfs:label ?o } }
                """.formatted(op.iri(), op.iri(), literal(op.label()), op.iri());
            case "deleteClass" -> """
                DELETE { <%s> ?p ?o } WHERE { <%s> ?p ?o };
                DELETE { ?s ?p <%s> } WHERE { ?s ?p <%s> }
                """.formatted(op.iri(), op.iri(), op.iri(), op.iri());
            case "addAnnotation" -> """
                INSERT DATA {
                  <%s> <%s> %s .
                }
                """.formatted(op.iri(), op.property(), literal(op.value()));
            case "updateAnnotation" -> """
                DELETE { <%s> <%s> ?oldValue }
                INSERT { <%s> <%s> %s }
                WHERE  { <%s> <%s> ?oldValue }
                """.formatted(op.iri(), op.property(), op.iri(), op.property(), literal(op.value()), op.iri(), op.property());
            case "deleteAnnotation" -> """
                DELETE DATA {
                  <%s> <%s> %s .
                }
                """.formatted(op.iri(), op.property(), literal(op.value()));
            case "addSubClassOf" -> """
                INSERT DATA {
                  <%s> rdfs:subClassOf <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "deleteSubClassOf" -> """
                DELETE DATA {
                  <%s> rdfs:subClassOf <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "addEquivalentClass" -> """
                INSERT DATA {
                  <%s> owl:equivalentClass <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "deleteEquivalentClass" -> """
                DELETE DATA {
                  <%s> owl:equivalentClass <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "addDisjointWith" -> """
                INSERT DATA {
                  <%s> owl:disjointWith <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "deleteDisjointWith" -> """
                DELETE DATA {
                  <%s> owl:disjointWith <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "createIndividual" -> """
                INSERT DATA {
                  <%s> a owl:NamedIndividual .
                  %s
                  <%s> a <%s> .
                }
                """.formatted(op.iri(), optionalLabel(op.iri(), op.label()), op.iri(), op.classIri());
            case "deleteIndividual" -> """
                DELETE { <%s> ?p ?o } WHERE { <%s> ?p ?o };
                DELETE { ?s ?p <%s> } WHERE { ?s ?p <%s> }
                """.formatted(op.iri(), op.iri(), op.iri(), op.iri());
            
            case "createObjectProperty" -> createPropertySparql(op.iri(), op.label(), op.parent(), "owl:ObjectProperty");
            case "createDataProperty" -> createPropertySparql(op.iri(), op.label(), op.parent(), "owl:DatatypeProperty");
            case "createAnnotationProperty" -> """
                INSERT DATA {
                  <%s> a owl:AnnotationProperty .
                  %s
                }
                """.formatted(op.iri(), optionalLabel(op.iri(), op.label()));
            case "deleteObjectProperty" -> """
                DELETE { <%s> ?p ?o } WHERE { <%s> ?p ?o };
                DELETE { ?s ?p <%s> } WHERE { ?s ?p <%s> }
                """.formatted(op.iri(), op.iri(), op.iri(), op.iri());
            case "deleteDataProperty" -> """
                DELETE { <%s> ?p ?o } WHERE { <%s> ?p ?o };
                DELETE { ?s ?p <%s> } WHERE { ?s ?p <%s> }
                """.formatted(op.iri(), op.iri(), op.iri(), op.iri());
            case "deleteAnnotationProperty" -> """
                DELETE { <%s> ?p ?o } WHERE { <%s> ?p ?o };
                DELETE { ?s ?p <%s> } WHERE { ?s ?p <%s> }
                """.formatted(op.iri(), op.iri(), op.iri(), op.iri());

            // --- Property Mutations ---
            case "addPropertyDomain" -> """
                INSERT DATA {
                  <%s> rdfs:domain <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "deletePropertyDomain" -> """
                DELETE DATA {
                  <%s> rdfs:domain <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "addPropertyRange" -> """
                INSERT DATA {
                  <%s> rdfs:range <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "deletePropertyRange" -> """
                DELETE DATA {
                  <%s> rdfs:range <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "addSubPropertyOf" -> """
                INSERT DATA {
                  <%s> rdfs:subPropertyOf <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "deleteSubPropertyOf" -> """
                DELETE DATA {
                  <%s> rdfs:subPropertyOf <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "addInverseProperty" -> """
                INSERT DATA {
                  <%s> owl:inverseOf <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "deleteInverseProperty" -> """
                DELETE DATA {
                  <%s> owl:inverseOf <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "addDisjointProperty" -> """
                INSERT DATA {
                  <%s> owl:propertyDisjointWith <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "deleteDisjointProperty" -> """
                DELETE DATA {
                  <%s> owl:propertyDisjointWith <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "addEquivalentProperty" -> """
                INSERT DATA {
                  <%s> owl:equivalentProperty <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "deleteEquivalentProperty" -> """
                DELETE DATA {
                  <%s> owl:equivalentProperty <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "addCharacteristic" -> """
                INSERT DATA {
                  <%s> a <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "deleteCharacteristic" -> """
                DELETE DATA {
                  <%s> a <%s> .
                }
                """.formatted(op.iri(), op.target());
            case "addAxiom" -> {
                // Placeholder for Manchester Syntax parsing
                // op.target() contains the expression
                // op.value() contains the axiom type (SubClassOf, EquivalentTo, etc.)
                // For now, we just log or ignore because we lack the parser
                yield ""; 
            }
            
            // --- Disjoint Union Mutations ---
            case "addDisjointUnion" -> {
                // op.iri() = class IRI
                // op.value() = comma-separated list of class IRIs for the union
                log.info("[MUTATION] Processing addDisjointUnion: iri={}, value={}", op.iri(), op.value());
                String[] memberIris = op.value() != null ? op.value().split(",") : new String[0];
                log.info("[MUTATION] Parsed {} member IRIs", memberIris.length);
                if (memberIris.length < 2) {
                    log.warn("[MUTATION] DisjointUnion requires at least 2 member classes, got {}", memberIris.length);
                    yield "";
                }
                String sparql = buildDisjointUnionSparql(op.iri(), memberIris);
                log.info("[MUTATION] Generated DisjointUnion SPARQL: {}", sparql);
                
                // Verify the sparql is not empty
                if (sparql == null || sparql.trim().isEmpty()) {
                    log.error("[MUTATION] Generated empty SPARQL for DisjointUnion!");
                    yield "";
                }
                
                yield sparql;
            }
            case "deleteDisjointUnion" -> {
                // op.iri() = class IRI
                // op.target() = list node ID to delete
                log.info("[MUTATION] Processing deleteDisjointUnion: iri={}, target={}", op.iri(), op.target());
                yield buildDeleteDisjointUnionSparql(op.iri(), op.target());
            }
            
            // --- Has Key Mutations ---
            case "addHasKey" -> {
                // op.iri() = class IRI
                // op.value() = comma-separated list of property IRIs for the key
                String[] propertyIris = op.value() != null ? op.value().split(",") : new String[0];
                if (propertyIris.length < 1) {
                    log.warn("[MUTATION] HasKey requires at least 1 property");
                    yield "";
                }
                yield buildHasKeySparql(op.iri(), propertyIris);
            }
            case "deleteHasKey" -> {
                // op.iri() = class IRI
                // op.target() = list node ID to delete
                yield buildDeleteHasKeySparql(op.iri(), op.target());
            }
            
            // --- Complex Class Expression Mutations ---
            case "addIntersection" -> {
                // op.iri() = class IRI to add intersection to
                // op.value() = comma-separated list of class IRIs for intersection
                // op.axiomType() = SubClassOf or EquivalentTo
                String[] memberIris = op.value() != null ? op.value().split(",") : new String[0];
                if (memberIris.length < 2) {
                    log.warn("[MUTATION] Intersection requires at least 2 member classes");
                    yield "";
                }
                yield buildIntersectionSparql(op.iri(), memberIris, op.axiomType());
            }
            case "deleteIntersection" -> {
                // op.iri() = class IRI
                // op.target() = blank node ID to delete
                // op.axiomType() = SubClassOf or EquivalentTo
                yield buildDeleteComplexExpressionSparql(op.iri(), op.target(), op.axiomType());
            }
            case "addUnion" -> {
                // op.iri() = class IRI to add union to
                // op.value() = comma-separated list of class IRIs for union
                // op.axiomType() = SubClassOf or EquivalentTo
                String[] memberIris = op.value() != null ? op.value().split(",") : new String[0];
                if (memberIris.length < 2) {
                    log.warn("[MUTATION] Union requires at least 2 member classes");
                    yield "";
                }
                yield buildUnionSparql(op.iri(), memberIris, op.axiomType());
            }
            case "deleteUnion" -> {
                // op.iri() = class IRI
                // op.target() = blank node ID to delete
                // op.axiomType() = SubClassOf or EquivalentTo
                yield buildDeleteComplexExpressionSparql(op.iri(), op.target(), op.axiomType());
            }
            case "addComplement" -> {
                // op.iri() = class IRI to add complement to
                // op.target() = class IRI to complement
                // op.axiomType() = SubClassOf or EquivalentTo
                yield buildComplementSparql(op.iri(), op.target(), op.axiomType());
            }
            case "deleteComplement" -> {
                // op.iri() = class IRI
                // op.target() = blank node ID to delete
                // op.axiomType() = SubClassOf or EquivalentTo
                yield buildDeleteComplexExpressionSparql(op.iri(), op.target(), op.axiomType());
            }
            case "addOneOf" -> {
                // op.iri() = class IRI to add oneOf enumeration to
                // op.value() = comma-separated list of individual IRIs
                // op.axiomType() = typically EquivalentTo
                String[] individualIris = op.value() != null ? op.value().split(",") : new String[0];
                if (individualIris.length < 1) {
                    log.warn("[MUTATION] OneOf requires at least 1 individual");
                    yield "";
                }
                yield buildOneOfSparql(op.iri(), individualIris, op.axiomType());
            }
            case "deleteOneOf" -> {
                // op.iri() = class IRI
                // op.target() = blank node ID to delete
                // op.axiomType() = SubClassOf or EquivalentTo
                yield buildDeleteComplexExpressionSparql(op.iri(), op.target(), op.axiomType());
            }
            
            // --- Object Restriction Mutations ---
            case "addObjectRestriction" -> {
                // Build an OWL restriction using structured data
                // op.iri() = class IRI to add restriction to
                // op.property() = object property IRI
                // op.restrictionType() = some, only, min, max, exactly, value
                // op.target() = filler class IRI
                // op.cardinality() = cardinality value (for min, max, exactly)
                // op.axiomType() = SubClassOf or EquivalentTo
                yield buildRestrictionSparql(op, false);
            }
            case "deleteObjectRestriction" -> {
                yield buildDeleteRestrictionSparql(op, false);
            }
            
            // --- Data Restriction Mutations ---
            case "addDataRestriction" -> {
                // Build an OWL data restriction
                // op.iri() = class IRI to add restriction to
                // op.property() = data property IRI
                // op.restrictionType() = some, only, min, max, exactly
                // op.target() = datatype IRI
                // op.cardinality() = cardinality value (for min, max, exactly)
                // op.axiomType() = SubClassOf or EquivalentTo
                yield buildRestrictionSparql(op, true);
            }
            case "deleteDataRestriction" -> {
                yield buildDeleteRestrictionSparql(op, true);
            }

            // --- Datatype Mutations ---
            case "createDatatype" -> """
                INSERT DATA {
                  <%s> a rdfs:Datatype .
                  %s
                }
                """.formatted(op.iri(), optionalLabel(op.iri(), op.label()));
            case "deleteDatatype" -> """
                DELETE { <%s> ?p ?o } WHERE { <%s> ?p ?o };
                DELETE { ?s ?p <%s> } WHERE { ?s ?p <%s> }
                """.formatted(op.iri(), op.iri(), op.iri(), op.iri());

            // --- Property Assertions on Individuals ---
            case "addObjectPropertyAssertion" -> """
                INSERT DATA {
                  <%s> <%s> <%s> .
                }
                """.formatted(op.iri(), op.property(), op.target());
            case "deleteObjectPropertyAssertion" -> """
                DELETE DATA {
                  <%s> <%s> <%s> .
                }
                """.formatted(op.iri(), op.property(), op.target());
            case "addDataPropertyAssertion" -> """
                INSERT DATA {
                  <%s> <%s> %s .
                }
                """.formatted(op.iri(), op.property(), literal(op.value()));
            case "deleteDataPropertyAssertion" -> """
                DELETE DATA {
                  <%s> <%s> %s .
                }
                """.formatted(op.iri(), op.property(), literal(op.value()));

            default -> throw new IllegalArgumentException("Unsupported op " + op.type());
        };
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
     * Build SPARQL INSERT to add an OWL restriction (object or data restriction)
     * Uses OWL 2 RDF syntax with blank nodes via INSERT WHERE pattern
     * 
     * @param op MutationOp containing restriction details
     * @param isDataRestriction true for data restrictions, false for object restrictions
     * @return SPARQL UPDATE string
     */
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
        
        // Build the restriction based on type using INSERT WHERE pattern
        // This allows blank nodes to be properly created
        String sparql = switch (restrictionType) {
            case "some" -> {
                String fillerPredicate = "owl:someValuesFrom";
                yield """
                    INSERT {
                      <%s> %s [
                        a owl:Restriction ;
                        owl:onProperty <%s> ;
                        %s <%s>
                      ] .
                    } WHERE { }
                    """.formatted(classIri, axiomPredicate, propertyIri, fillerPredicate, fillerIri);
            }
            case "only" -> {
                String fillerPredicate = "owl:allValuesFrom";
                yield """
                    INSERT {
                      <%s> %s [
                        a owl:Restriction ;
                        owl:onProperty <%s> ;
                        %s <%s>
                      ] .
                    } WHERE { }
                    """.formatted(classIri, axiomPredicate, propertyIri, fillerPredicate, fillerIri);
            }
            case "value" -> {
                yield """
                    INSERT {
                      <%s> %s [
                        a owl:Restriction ;
                        owl:onProperty <%s> ;
                        owl:hasValue <%s>
                      ] .
                    } WHERE { }
                    """.formatted(classIri, axiomPredicate, propertyIri, fillerIri);
            }
            case "min" -> {
                int card = cardinality != null ? cardinality : 1;
                String onClassPredicate = isDataRestriction ? "owl:onDataRange" : "owl:onClass";
                yield """
                    INSERT {
                      <%s> %s [
                        a owl:Restriction ;
                        owl:onProperty <%s> ;
                        owl:minQualifiedCardinality "%d"^^xsd:nonNegativeInteger ;
                        %s <%s>
                      ] .
                    } WHERE { }
                    """.formatted(classIri, axiomPredicate, propertyIri, card, onClassPredicate, fillerIri);
            }
            case "max" -> {
                int card = cardinality != null ? cardinality : 1;
                String onClassPredicate = isDataRestriction ? "owl:onDataRange" : "owl:onClass";
                yield """
                    INSERT {
                      <%s> %s [
                        a owl:Restriction ;
                        owl:onProperty <%s> ;
                        owl:maxQualifiedCardinality "%d"^^xsd:nonNegativeInteger ;
                        %s <%s>
                      ] .
                    } WHERE { }
                    """.formatted(classIri, axiomPredicate, propertyIri, card, onClassPredicate, fillerIri);
            }
            case "exactly" -> {
                int card = cardinality != null ? cardinality : 1;
                String onClassPredicate = isDataRestriction ? "owl:onDataRange" : "owl:onClass";
                yield """
                    INSERT {
                      <%s> %s [
                        a owl:Restriction ;
                        owl:onProperty <%s> ;
                        owl:qualifiedCardinality "%d"^^xsd:nonNegativeInteger ;
                        %s <%s>
                      ] .
                    } WHERE { }
                    """.formatted(classIri, axiomPredicate, propertyIri, card, onClassPredicate, fillerIri);
            }
            default -> {
                log.warn("[MUTATION] Unknown restriction type: {}", restrictionType);
                yield "";
            }
        };
        
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
        
        // Build WHERE clause that matches the EXACT restriction including the filler
        // (We use a nested SELECT approach now, so we don't need to build complex patterns here)
        
        // SIMPLIFIED APPROACH: Delete by matching property + filler without requiring owl:Restriction type
        // Some restrictions may not have explicit rdf:type, so we just match on the properties
        String sparql = """
            DELETE {
              <%s> %s ?restriction .
              ?restriction ?p ?o .
            }
            WHERE {
              <%s> %s ?restriction .
              ?restriction owl:onProperty <%s> ;
                          %s <%s> ;
                          ?p ?o .
            }
            """.formatted(classIri, axiomPredicate, classIri, axiomPredicate, propertyIri, fillerPredicate, fillerIri);
        
        log.info("[MUTATION] Generated delete restriction SPARQL (simplified - no type check):");
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
        String axiomType        // EquivalentTo, SubClassOf
    ) {}
}
