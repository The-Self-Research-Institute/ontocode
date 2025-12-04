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
                .collect(Collectors.joining("\n;\n"));
        
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

    public record MutationOp(
        String type, 
        String iri, 
        String label, 
        String parent,
        String property,
        String value,
        String target,
        String classIri
    ) {}
}
