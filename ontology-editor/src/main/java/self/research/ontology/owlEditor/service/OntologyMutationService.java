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
        
        datasetService.execUpdate(projectId, sparql);
        
        log.info("[MUTATION] ✅ Mutations applied successfully!");

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
                String[] memberIris = op.value() != null ? op.value().split(",") : new String[0];
                if (memberIris.length < 2) {
                    log.warn("[MUTATION] DisjointUnion requires at least 2 member classes");
                    yield "";
                }
                yield buildDisjointUnionSparql(op.iri(), memberIris);
            }
            case "deleteDisjointUnion" -> {
                // op.iri() = class IRI
                // op.target() = list node ID to delete
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
        // String fillerIri = op.target(); // Could be used for more precise matching
        String axiomType = op.axiomType();
        
        log.info("[MUTATION] buildDeleteRestrictionSparql called:");
        log.info("[MUTATION]   classIri: {}", classIri);
        log.info("[MUTATION]   propertyIri: {}", propertyIri);
        log.info("[MUTATION]   restrictionType: {}", restrictionType);
        
        // Determine the axiom predicate
        String axiomPredicate = switch (axiomType) {
            case "EquivalentTo" -> "owl:equivalentClass";
            case "DisjointWith" -> "owl:disjointWith";
            default -> "rdfs:subClassOf";
        };
        
        // Determine the filler predicate based on restriction type
        String fillerPredicate = switch (restrictionType) {
            case "some" -> isDataRestriction ? "owl:someValuesFrom" : "owl:someValuesFrom";
            case "only" -> isDataRestriction ? "owl:allValuesFrom" : "owl:allValuesFrom";
            case "value" -> "owl:hasValue";
            case "min" -> isDataRestriction ? "owl:minQualifiedCardinality" : "owl:minQualifiedCardinality";
            case "max" -> isDataRestriction ? "owl:maxQualifiedCardinality" : "owl:maxQualifiedCardinality";
            case "exactly" -> isDataRestriction ? "owl:qualifiedCardinality" : "owl:qualifiedCardinality";
            default -> "";
        };
        
        if (fillerPredicate.isEmpty()) {
            return "";
        }
        
        // Delete the restriction blank node and all its properties
        String sparql = """
            DELETE {
              ?restriction ?p ?o .
              <%s> %s ?restriction .
            }
            WHERE {
              <%s> %s ?restriction .
              ?restriction a owl:Restriction ;
                          owl:onProperty <%s> .
              ?restriction ?p ?o .
            }
            """.formatted(classIri, axiomPredicate, classIri, axiomPredicate, propertyIri);
        
        log.info("[MUTATION]   Generated delete restriction SPARQL: {}", sparql);
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
        
        // Build an RDF list using blank nodes
        // Format: _:b1 rdf:first <member1>; rdf:rest _:b2. _:b2 rdf:first <member2>; rdf:rest rdf:nil.
        StringBuilder insertBuilder = new StringBuilder();
        insertBuilder.append("INSERT {\n");
        insertBuilder.append("  <").append(classIri).append("> owl:disjointUnionOf _:list0 .\n");
        
        for (int i = 0; i < memberIris.length; i++) {
            String currentList = "_:list" + i;
            String nextList = (i == memberIris.length - 1) ? "rdf:nil" : "_:list" + (i + 1);
            insertBuilder.append("  ").append(currentList)
                .append(" rdf:first <").append(memberIris[i].trim()).append("> ;\n");
            insertBuilder.append("             rdf:rest ").append(nextList).append(" .\n");
        }
        
        insertBuilder.append("} WHERE { }");
        
        String sparql = insertBuilder.toString();
        log.info("[MUTATION]   Generated disjoint union SPARQL: {}", sparql);
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
