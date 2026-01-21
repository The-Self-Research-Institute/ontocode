package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.model.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for managing ontology-level metadata: annotations, imports, and general class axioms
 */
@Slf4j
@Service
public class OntologyMetadataService {

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX dc: <http://purl.org/dc/elements/1.1/>
        PREFIX dcterms: <http://purl.org/dc/terms/>
        """;

    private final GraphDBDatasetService datasetService;
    private final ProjectMetadataService projectMetadataService;
    private final Map<String, String> ontologyIriCache = new java.util.concurrent.ConcurrentHashMap<>();

    public OntologyMetadataService(GraphDBDatasetService datasetService, ProjectMetadataService projectMetadataService) {
        this.datasetService = datasetService;
        this.projectMetadataService = projectMetadataService;
    }

    /**
     * Get all metadata for an ontology
     */
    public Map<String, Object> getMetadata(String projectId) {
        Map<String, Object> metadata = new HashMap<>();
        
        // 1. Get base metadata from MongoDB
        projectMetadataService.readMeta(projectId).ifPresent(metadata::putAll);
        
        // 2. Merge with dynamic metrics from GraphDB
        metadata.putAll(getDynamicMetrics(projectId));
        
        // 3. Get ontology IRI and version IRI
        String ontologyIri = getOntologyIri(projectId);
        metadata.put("ontologyIRI", ontologyIri);
        
        if (ontologyIri != null) {
            String query = PREFIXES + String.format("SELECT ?v WHERE { <%s> owl:versionIRI ?v }", ontologyIri);
            try {
                TupleQueryResult rs = datasetService.execSelect(projectId, query);
                if (rs.hasNext()) {
                    metadata.put("versionIRI", rs.next().getValue("v").stringValue());
                }
            } catch (Exception e) {
                log.error("Error fetching version IRI", e);
            }
        }
        
        // 4. Add other metadata components
        metadata.put("prefixes", getPrefixes(projectId));
        metadata.put("annotations", getOntologyAnnotations(projectId));
        metadata.put("imports", getOntologyImports(projectId));
        metadata.put("axioms", getGeneralClassAxioms(projectId));
        
        return metadata;
    }

    /**
     * Get all ontology annotations (dc:title, dc:creator, rdfs:label, rdfs:comment, etc.)
     */
    public List<Map<String, String>> getOntologyAnnotations(String projectId) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) {
            log.info("No ontology IRI found for project {}, returning empty annotations", projectId);
            return new ArrayList<>();
        }

        String formattedOntologyIri = formatResource(ontologyIri);
        String query = PREFIXES + String.format("""
            SELECT ?property ?value (LANG(?value) as ?lang) (DATATYPE(?value) as ?datatype) WHERE {
              %s ?property ?value .
              FILTER(?property != rdf:type || ?value != owl:Ontology)
              FILTER(?property != owl:imports)
              FILTER(?property != owl:versionIRI)
              FILTER(?property != owl:versionInfo)
              
              # Keep common annotation properties and any other non-owl/rdf internal properties
              FILTER(
                strstarts(str(?property), str(rdfs:)) ||
                strstarts(str(?property), str(dc:)) ||
                strstarts(str(?property), str(dcterms:)) ||
                strstarts(str(?property), str(owl:)) ||
                (!strstarts(str(?property), "http://www.w3.org/1999/02/22-rdf-syntax-ns#") &&
                 !strstarts(str(?property), "http://www.w3.org/2002/07/owl#"))
              )
            }
            """, formattedOntologyIri);

        List<Map<String, String>> annotations = new ArrayList<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("property") && sol.hasBinding("value")) {
                    Map<String, String> ann = new LinkedHashMap<>();
                    ann.put("property", sol.getValue("property").stringValue());
                    Value valueNode = sol.getValue("value");
                    String value = valueNode.isLiteral() ? valueNode.stringValue() : valueNode.toString();
                    ann.put("value", value);
                    
                    if (sol.hasBinding("lang") && !sol.getValue("lang").stringValue().isEmpty()) {
                        ann.put("language", sol.getValue("lang").stringValue());
                    }
                    if (sol.hasBinding("datatype")) {
                        String dt = sol.getValue("datatype").stringValue();
                        if (!dt.equals("http://www.w3.org/2001/XMLSchema#string")) {
                            ann.put("datatype", dt);
                        }
                    }
                    
                    annotations.add(ann);
                }
            }
        } catch (Exception e) {
            log.error("Error fetching ontology annotations for project " + projectId, e);
        }
        return annotations;
    }

    /**
     * Add an ontology annotation
     */
    public void addOntologyAnnotation(String projectId, String propertyIri, String value, String language, String datatype) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) {
            // If no ontology triple exists, create one using a stable IRI based on project ID
            ontologyIri = "http://ontocode.org/resource/ontology/" + projectId;
            String initUpdate = PREFIXES + String.format("INSERT DATA { <%s> a owl:Ontology . }", ontologyIri);
            datasetService.execUpdate(projectId, initUpdate);
            ontologyIriCache.put(projectId, ontologyIri);
            ontologyIri = "<" + ontologyIri + ">";
        } else {
            // Format the ontology IRI for SPARQL
            ontologyIri = formatResource(ontologyIri);
        }

        String prop = formatResource(propertyIri);
        String literal = formatLiteral(value, language, datatype);
        
        String update = PREFIXES + String.format("""
            INSERT DATA {
              %s %s %s .
            }
            """, ontologyIri, prop, literal);

        datasetService.execUpdate(projectId, update);
    }

    /**
     * Update an ontology annotation
     */
        public void updateOntologyAnnotation(
                        String projectId,
                        String propertyIri,
                        String oldValue,
                        String newValue,
                        String language,
                        String datatype,
                        String originalPropertyIri) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) {
            throw new RuntimeException("Ontology IRI not found for project " + projectId);
        }
        ontologyIri = formatResource(ontologyIri);

                String insertProp = formatResource(propertyIri);
                String deleteProp = formatResource(
                                (originalPropertyIri != null && !originalPropertyIri.isBlank()) ? originalPropertyIri : propertyIri
                );
        String newLiteral = formatLiteral(newValue, language, datatype);
        
        // Use a robust DELETE/INSERT/WHERE that matches by string value if exact match fails
        String update = PREFIXES + String.format("""
            DELETE {
                            %s %s ?old .
            }
            INSERT {
                            %s %s %s .
            }
            WHERE {
                            %s %s ?old .
              FILTER(STR(?old) = "%s")
            }
                        """, ontologyIri, deleteProp,
                                 ontologyIri, insertProp, newLiteral,
                                 ontologyIri, deleteProp, escapeString(oldValue));

        datasetService.execUpdate(projectId, update);
    }

    /**
     * Delete an ontology annotation
     */
    public void deleteOntologyAnnotation(String projectId, String propertyIri, String value, String language) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) {
            throw new RuntimeException("Ontology IRI not found for project " + projectId);
        }
        ontologyIri = formatResource(ontologyIri);

        String prop = formatResource(propertyIri);
        
        // Use robust delete that matches by string value
        String update = PREFIXES + String.format("""
            DELETE {
              %s %s ?v .
            }
            WHERE {
              %s %s ?v .
              FILTER(STR(?v) = "%s")
            }
            """, ontologyIri, prop, 
                 ontologyIri, prop, escapeString(value));

        datasetService.execUpdate(projectId, update);
    }

    private String formatLiteral(String value, String language, String datatype) {
        String escaped = escapeString(value);
        if (language != null && !language.isEmpty()) {
            return String.format("\"%s\"@%s", escaped, language);
        } else if (datatype != null && !datatype.isEmpty() && !datatype.equals("xsd:string") && !datatype.endsWith("#string")) {
            if (datatype.startsWith("http")) {
                return String.format("\"%s\"^^<%s>", escaped, datatype);
            } else {
                return String.format("\"%s\"^^%s", escaped, datatype);
            }
        } else {
            return String.format("\"%s\"", escaped);
        }
    }

    /**
     * Get all ontology imports
     */
    public List<String> getOntologyImports(String projectId) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) {
            log.warn("No ontology IRI found for project {}, returning empty imports", projectId);
            return new ArrayList<>();
        }
        
        String formattedOntologyIri = formatResource(ontologyIri);
        String query = PREFIXES + String.format("""
            SELECT ?import WHERE {
              %s owl:imports ?import .
            }
            """, formattedOntologyIri);
        
        List<String> imports = new ArrayList<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("import")) {
                    String importIri = sol.getValue("import").stringValue();
                    imports.add(importIri);
                    // Log local imports for debugging
                    if (!importIri.startsWith("http://") && !importIri.startsWith("https://")) {
                        log.info("Local import found: {}", importIri);
                    }
                }
            }
            log.info("Retrieved {} imports for project {} (local: {}, remote: {})", 
                imports.size(), projectId,
                imports.stream().filter(i -> !i.startsWith("http://") && !i.startsWith("https://")).count(),
                imports.stream().filter(i -> i.startsWith("http://") || i.startsWith("https://")).count());
        } catch (Exception e) {
            log.error("Error fetching ontology imports for project " + projectId, e);
        }
        return imports;
    }

    /**
     * Add an ontology import
     */
    public void addOntologyImport(String projectId, String importIri) {
        log.info("Adding import '{}' to project {}", importIri, projectId);
        
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) {
            // If no ontology triple exists, create one using a stable IRI
            ontologyIri = "http://ontocode.org/resource/ontology/" + projectId;
            String initUpdate = PREFIXES + String.format("INSERT DATA { <%s> a owl:Ontology . }", ontologyIri);
            datasetService.execUpdate(projectId, initUpdate);
            ontologyIri = "<" + ontologyIri + ">";
            log.info("Created new ontology IRI: {}", ontologyIri);
        } else {
            ontologyIri = formatResource(ontologyIri);
        }

        // Handle relative imports (starting with ./ or ../) differently
        // Relative imports should not be wrapped in angle brackets if they don't have a scheme
        String formattedImportIri;
        if (importIri.startsWith("./") || importIri.startsWith("../")) {
            // For relative imports, wrap in angle brackets to make them valid RDF IRIs
            formattedImportIri = "<" + importIri + ">";
            log.info("Relative import detected: {}", importIri);
        } else if (importIri.startsWith("http://") || importIri.startsWith("https://") || 
                   importIri.startsWith("ftp://") || importIri.startsWith("file://")) {
            // Absolute IRIs (URLs or file:// URIs)
            formattedImportIri = "<" + importIri + ">";
            if (importIri.startsWith("file://")) {
                log.info("Local file import (file://) detected: {}", importIri);
            } else {
                log.info("Remote import detected: {}", importIri);
            }
        } else {
            // Bare filenames or other formats - treat as relative
            formattedImportIri = "<./" + importIri + ">";
            log.info("Bare filename detected, converting to relative: {} -> ./{}", importIri, importIri);
        }

        String update = PREFIXES + String.format("""
            INSERT DATA {
              %s owl:imports %s .
            }
            """, ontologyIri, formattedImportIri);

        log.debug("SPARQL Update: {}", update);
        datasetService.execUpdate(projectId, update);
        log.info("✅ Successfully added import '{}' to project {}", importIri, projectId);
    }

    /**
     * Delete an ontology import
     */
    public void deleteOntologyImport(String projectId, String importIri) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) {
            throw new RuntimeException("Ontology IRI not found");
        }
        ontologyIri = formatResource(ontologyIri);

        // Handle relative imports (starting with ./ or ../) differently
        String formattedImportIri;
        if (importIri.startsWith("./") || importIri.startsWith("../")) {
            formattedImportIri = "<" + importIri + ">";
        } else if (importIri.startsWith("http://") || importIri.startsWith("https://") || 
                   importIri.startsWith("ftp://") || importIri.startsWith("file://")) {
            formattedImportIri = "<" + importIri + ">";
        } else {
            formattedImportIri = "<./" + importIri + ">";
        }

        String update = PREFIXES + String.format("""
            DELETE {
              %s owl:imports %s .
            }
            WHERE {
              %s owl:imports %s .
            }
            """, ontologyIri, formattedImportIri, ontologyIri, formattedImportIri);

        datasetService.execUpdate(projectId, update);
    }

    /**
     * Get all General Class Axioms (GCIs)
     */
    public List<Map<String, Object>> getGeneralClassAxioms(String projectId) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) return new ArrayList<>();
        String formattedOntologyIri = formatResource(ontologyIri);

        // We look for both real RDF GCIs (blank node subjects) and our custom stored GCIs
        String query = PREFIXES + String.format("""
            SELECT ?gci ?sub ?super WHERE {
              {
                %s <http://ontocode.org/resource/gci> ?gci .
              }
              UNION
              {
                ?sub rdfs:subClassOf ?super .
                FILTER(isBlank(?sub))
                BIND(CONCAT(STR(?sub), " SubClassOf ", STR(?super)) AS ?gci)
              }
            }
            """, formattedOntologyIri);

        List<Map<String, Object>> gcis = new ArrayList<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("gci")) {
                    Map<String, Object> gci = new LinkedHashMap<>();
                    String value = sol.getValue("gci").stringValue();
                    gci.put("value", value);
                    
                    // Try to extract sub and super if they exist as bindings
                    if (sol.hasBinding("sub")) gci.put("subClass", sol.getValue("sub").stringValue());
                    if (sol.hasBinding("super")) gci.put("superClass", sol.getValue("super").stringValue());
                    
                    // If sub/super are missing (custom GCI string), parse the value
                    if (!gci.containsKey("subClass") && value.contains(" SubClassOf ")) {
                        String[] parts = value.split(" SubClassOf ");
                        if (parts.length == 2) {
                            gci.put("subClass", parts[0]);
                            gci.put("superClass", parts[1]);
                        }
                    }
                    
                    gcis.add(gci);
                }
            }
        } catch (Exception e) {
            log.error("Error fetching general class axioms for project " + projectId, e);
        }
        return gcis;
    }

    /**
     * Add a General Class Axiom (GCI)
     */
    public void addGCI(String projectId, String subClassExpr, String superClassExpr) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) {
            ontologyIri = "http://ontocode.org/resource/ontology/" + projectId;
            datasetService.execUpdate(projectId, PREFIXES + String.format("INSERT DATA { <%s> a owl:Ontology . }", ontologyIri));
            ontologyIri = "<" + ontologyIri + ">";
        } else {
            ontologyIri = formatResource(ontologyIri);
        }

        String gciValue = subClassExpr + " SubClassOf " + superClassExpr;
        String update = PREFIXES + String.format("""
            INSERT DATA {
              %s <http://ontocode.org/resource/gci> "%s" .
            }
            """, ontologyIri, escapeString(gciValue));

        datasetService.execUpdate(projectId, update);
    }

    /**
     * Delete a General Class Axiom (GCI)
     */
    public void deleteGCI(String projectId, String gciValue) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) return;
        ontologyIri = formatResource(ontologyIri);

        String update = PREFIXES + String.format("""
            DELETE {
              %s <http://ontocode.org/resource/gci> "%s" .
            }
            WHERE {
              %s <http://ontocode.org/resource/gci> "%s" .
            }
            """, ontologyIri, escapeString(gciValue), ontologyIri, escapeString(gciValue));

        datasetService.execUpdate(projectId, update);
    }

    /**
     * Update ontology IRI and version IRI
     */
    public void updateOntologyIRIs(String projectId, String newOntologyIri, String newVersionIri) {
        String oldOntologyIri = getOntologyIri(projectId);
        String formattedOld = oldOntologyIri != null ? formatResource(oldOntologyIri) : null;
        String formattedNew = formatResource(newOntologyIri);

        StringBuilder update = new StringBuilder(PREFIXES);
        update.append("DELETE { ");
        if (formattedOld != null) {
            update.append(formattedOld).append(" a owl:Ontology . ");
            update.append(formattedOld).append(" owl:versionIRI ?v . ");
        }
        update.append(" } INSERT { ");
        update.append(formattedNew).append(" a owl:Ontology . ");
        if (newVersionIri != null && !newVersionIri.isEmpty()) {
            update.append(formattedNew).append(" owl:versionIRI <").append(newVersionIri).append("> . ");
        }
        update.append(" } WHERE { ");
        if (formattedOld != null) {
            update.append("OPTIONAL { ").append(formattedOld).append(" owl:versionIRI ?v } ");
        } else {
            update.append("BIND(1 as ?dummy) ");
        }
        update.append(" }");

        datasetService.execUpdate(projectId, update.toString());
        
        // Update cache
        ontologyIriCache.put(projectId, newOntologyIri);
    }

    /**
     * Get dynamic metrics from GraphDB
     */
    public Map<String, Object> getDynamicMetrics(String projectId) {
        Map<String, Object> metrics = new HashMap<>();
        
        log.info("Calculating dynamic metrics for project: {}", projectId);
        
        int classCount = getCount(projectId, "owl:Class");
        int objectPropertyCount = getCount(projectId, "owl:ObjectProperty");
        int dataPropertyCount = getCount(projectId, "owl:DatatypeProperty");
        int annotationPropertyCount = getCount(projectId, "owl:AnnotationProperty");
        int individualCount = getCount(projectId, "owl:NamedIndividual");
        
        metrics.put("classCount", classCount);
        metrics.put("objectPropertyCount", objectPropertyCount);
        metrics.put("dataPropertyCount", dataPropertyCount);
        metrics.put("annotationPropertyCount", annotationPropertyCount);
        metrics.put("individualCount", individualCount);
        
        // Axiom count (total triples in the graph)
        int tripleCount = (int) datasetService.getDatasetSize(projectId);
        metrics.put("axiomCount", tripleCount);
        metrics.put("tripleCount", tripleCount);
        
        // Declaration axioms = sum of entity types
        metrics.put("declarationAxiomCount", classCount + objectPropertyCount + dataPropertyCount + annotationPropertyCount + individualCount);
        
        // Logical axioms (heuristic: total - declarations - metadata)
        metrics.put("logicalAxiomCount", Math.max(0, tripleCount - (classCount + objectPropertyCount + dataPropertyCount + annotationPropertyCount + individualCount)));

        // Specific axiom counts
        metrics.put("subClassOfAxiomCount", getPredicateCount(projectId, "rdfs:subClassOf"));
        metrics.put("equivalentClassesAxiomCount", getPredicateCount(projectId, "owl:equivalentClass"));
        metrics.put("disjointClassesAxiomCount", getPredicateCount(projectId, "owl:disjointWith"));
        
        metrics.put("subObjectPropertyOfAxiomCount", getPredicateCount(projectId, "rdfs:subPropertyOf"));
        metrics.put("equivalentObjectPropertiesAxiomCount", getPredicateCount(projectId, "owl:equivalentProperty"));
        metrics.put("inverseObjectPropertiesAxiomCount", getPredicateCount(projectId, "owl:inverseOf"));
        metrics.put("disjointObjectPropertiesAxiomCount", getPredicateCount(projectId, "owl:propertyDisjointWith"));
        metrics.put("functionalObjectPropertyAxiomCount", getCount(projectId, "owl:FunctionalProperty"));
        metrics.put("inverseFunctionalObjectPropertyAxiomCount", getCount(projectId, "owl:InverseFunctionalProperty"));
        metrics.put("transitiveObjectPropertyAxiomCount", getCount(projectId, "owl:TransitiveProperty"));
        metrics.put("symmetricObjectPropertyAxiomCount", getCount(projectId, "owl:SymmetricProperty"));
        metrics.put("asymmetricObjectPropertyAxiomCount", getCount(projectId, "owl:AsymmetricProperty"));
        metrics.put("reflexiveObjectPropertyAxiomCount", getCount(projectId, "owl:ReflexiveProperty"));
        metrics.put("irreflexiveObjectPropertyAxiomCount", getCount(projectId, "owl:IrreflexiveProperty"));
        metrics.put("objectPropertyDomainAxiomCount", getPredicateCount(projectId, "rdfs:domain"));
        metrics.put("objectPropertyRangeAxiomCount", getPredicateCount(projectId, "rdfs:range"));
        
        metrics.put("subDataPropertyOfAxiomCount", getPredicateCount(projectId, "rdfs:subPropertyOf"));
        metrics.put("equivalentDataPropertiesAxiomCount", getPredicateCount(projectId, "owl:equivalentProperty"));
        metrics.put("disjointDataPropertiesAxiomCount", getPredicateCount(projectId, "owl:propertyDisjointWith"));
        metrics.put("functionalDataPropertyAxiomCount", getCount(projectId, "owl:FunctionalProperty"));
        metrics.put("dataPropertyDomainAxiomCount", getPredicateCount(projectId, "rdfs:domain"));
        metrics.put("dataPropertyRangeAxiomCount", getPredicateCount(projectId, "rdfs:range"));
        
        metrics.put("classAssertionAxiomCount", getPredicateCount(projectId, "rdf:type"));
        metrics.put("objectPropertyAssertionAxiomCount", getTripleCountWithPredicateType(projectId, "owl:ObjectProperty"));
        metrics.put("dataPropertyAssertionAxiomCount", getTripleCountWithPredicateType(projectId, "owl:DatatypeProperty"));
        metrics.put("sameIndividualAxiomCount", getPredicateCount(projectId, "owl:sameAs"));
        metrics.put("differentIndividualsAxiomCount", getPredicateCount(projectId, "owl:differentFrom"));
        metrics.put("negativeObjectPropertyAssertionAxiomCount", getCount(projectId, "owl:NegativePropertyAssertion")); // Simplified
        metrics.put("negativeDataPropertyAssertionAxiomCount", 0); // Usually same as above in RDF
        
        metrics.put("subPropertyChainOfAxiomCount", getPredicateCount(projectId, "owl:propertyChainAxiom"));
        
        // Annotation axioms
        metrics.put("annotationAssertionAxiomCount", getTripleCountWithPredicateType(projectId, "owl:AnnotationProperty"));
        metrics.put("annotationPropertyDomainAxiomCount", getPredicateCount(projectId, "rdfs:domain")); // Note: shared with object/data properties
        metrics.put("annotationPropertyRangeAxiomCount", getPredicateCount(projectId, "rdfs:range"));   // Note: shared with object/data properties
        metrics.put("subAnnotationPropertyOfAxiomCount", getPredicateCount(projectId, "rdfs:subPropertyOf")); // Note: shared
        
        // GCI count
        metrics.put("gciCount", getGCICount(projectId));
        
        metrics.put("ontologyIRI", getOntologyIri(projectId));
        
        return metrics;
    }

    private int getTripleCountWithPredicateType(String projectId, String type) {
        String query = PREFIXES + String.format("SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o . ?p a %s . }", type);
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("count")) {
                    return Integer.parseInt(sol.getValue("count").stringValue());
                }
            }
        } catch (Exception e) {
            log.error("Error getting triple count for predicate type " + type, e);
        }
        return 0;
    }

    private int getPredicateCount(String projectId, String predicate) {
        String query = PREFIXES + String.format("SELECT (COUNT(*) AS ?count) WHERE { ?s %s ?o . }", predicate);
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("count")) {
                    return Integer.parseInt(sol.getValue("count").stringValue());
                }
            }
        } catch (Exception e) {
            log.error("Error getting count for predicate " + predicate, e);
        }
        return 0;
    }

    private int getCount(String projectId, String type) {
        String query = PREFIXES + String.format("SELECT (COUNT(DISTINCT ?s) AS ?count) WHERE { ?s a %s . }", type);
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("count")) {
                    return Integer.parseInt(sol.getValue("count").stringValue());
                }
            }
        } catch (Exception e) {
            log.error("Error getting count for type " + type, e);
        }
        return 0;
    }

    private int getGCICount(String projectId) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) return 0;
        String formattedOntologyIri = formatResource(ontologyIri);

        String query = PREFIXES + String.format("""
            SELECT (COUNT(?gci) AS ?count) WHERE {
              {
                %s <http://ontocode.org/resource/gci> ?gci .
              }
              UNION
              {
                ?sub rdfs:subClassOf ?super .
                FILTER(isBlank(?sub))
                BIND(?sub AS ?gci)
              }
            }
            """, formattedOntologyIri);
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("count")) {
                    return Integer.parseInt(sol.getValue("count").stringValue());
                }
            }
        } catch (Exception e) {
            log.error("Error getting GCI count", e);
        }
        return 0;
    }

    /**
     * Get all prefixes
     */
    public List<Map<String, String>> getPrefixes(String projectId) {
        Map<String, String> prefixMap = new HashMap<>();
        
        // 1. Try to get from MongoDB metadata
        Optional<Map<String, Object>> meta = projectMetadataService.readMeta(projectId);
        if (meta.isPresent() && meta.get().containsKey("prefixes")) {
            Object prefixesObj = meta.get().get("prefixes");
            if (prefixesObj instanceof Map) {
                Map<?, ?> rawMap = (Map<?, ?>) prefixesObj;
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    prefixMap.put(entry.getKey().toString(), entry.getValue().toString());
                }
            } else if (prefixesObj instanceof List) {
                // Handle list of objects format: [{prefix: "...", namespace: "..."}, ...]
                List<?> list = (List<?>) prefixesObj;
                for (Object item : list) {
                    if (item instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) item;
                        Object p = map.get("prefix");
                        Object n = map.get("namespace");
                        if (p != null && n != null) {
                            prefixMap.put(p.toString(), n.toString());
                        }
                    }
                }
            }
        }

        // 2. Always merge with GraphDB repository namespaces to ensure we see everything
        log.debug("Merging MongoDB prefixes with GraphDB namespaces for project {}", projectId);
        Map<String, String> graphdbPrefixes = datasetService.getPrefixes(projectId);
        for (Map.Entry<String, String> entry : graphdbPrefixes.entrySet()) {
            // Only add if not already present in MongoDB (MongoDB takes precedence for custom renames)
            if (!prefixMap.containsKey(entry.getKey())) {
                prefixMap.put(entry.getKey(), entry.getValue());
            }
        }

        // Convert to list of objects for frontend compatibility
        List<Map<String, String>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : prefixMap.entrySet()) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("prefix", entry.getKey());
            p.put("namespace", entry.getValue());
            result.add(p);
        }
        
        // Sort by prefix name
        result.sort(Comparator.comparing(m -> m.get("prefix")));
        
        return result;
    }

    /**
     * Update or add a prefix
     */
    public void updatePrefix(String projectId, String prefix, String iri, String oldPrefix) {
        // 1. Update in MongoDB
        Optional<Map<String, Object>> metaOpt = projectMetadataService.readMeta(projectId);
        Map<String, Object> meta = metaOpt.orElse(new HashMap<>());
        
        Map<String, String> prefixes = new HashMap<>();
        Object existingPrefixes = meta.get("prefixes");
        if (existingPrefixes instanceof Map) {
            Map<?, ?> rawMap = (Map<?, ?>) existingPrefixes;
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                prefixes.put(entry.getKey().toString(), entry.getValue().toString());
            }
        } else if (existingPrefixes instanceof List) {
            List<?> list = (List<?>) existingPrefixes;
            for (Object item : list) {
                if (item instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) item;
                    Object p = map.get("prefix");
                    Object n = map.get("namespace");
                    if (p != null && n != null) {
                        prefixes.put(p.toString(), n.toString());
                    }
                }
            }
        }
        
        // If MongoDB prefixes are empty, try to pull from GraphDB first to avoid wiping them
        if (prefixes.isEmpty()) {
            log.info("MongoDB prefixes empty for project {}, pulling from GraphDB before update", projectId);
            prefixes.putAll(datasetService.getPrefixes(projectId));
        }
        
        // If we are renaming a prefix, remove the old one
        if (oldPrefix != null && !oldPrefix.equals(prefix)) {
            prefixes.remove(oldPrefix);
            datasetService.removePrefix(projectId, oldPrefix);
        }
        
        prefixes.put(prefix, iri);
        meta.put("prefixes", prefixes);
        projectMetadataService.writeMeta(projectId, meta);

        // 2. Also update in GraphDB (repository-wide)
        datasetService.updatePrefix(projectId, prefix, iri);
    }

    /**
     * Delete a prefix
     */
    public void deletePrefix(String projectId, String prefix) {
        // 1. Update in MongoDB
        Optional<Map<String, Object>> metaOpt = projectMetadataService.readMeta(projectId);
        if (metaOpt.isPresent()) {
            Map<String, Object> meta = metaOpt.get();
            Map<String, String> prefixes = new HashMap<>();
            Object existingPrefixes = meta.get("prefixes");
            if (existingPrefixes instanceof Map) {
                Map<?, ?> rawMap = (Map<?, ?>) existingPrefixes;
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    prefixes.put(entry.getKey().toString(), entry.getValue().toString());
                }
            } else if (existingPrefixes instanceof List) {
                List<?> list = (List<?>) existingPrefixes;
                for (Object item : list) {
                    if (item instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) item;
                        Object p = map.get("prefix");
                        Object n = map.get("namespace");
                        if (p != null && n != null) {
                            prefixes.put(p.toString(), n.toString());
                        }
                    }
                }
            }
            
            if (prefixes.containsKey(prefix)) {
                prefixes.remove(prefix);
                meta.put("prefixes", prefixes);
                projectMetadataService.writeMeta(projectId, meta);
            }
        }

        // 2. Also remove from GraphDB
        datasetService.removePrefix(projectId, prefix);
    }

    /**
     * Get the ontology IRI for a project
     */
    private String getOntologyIri(String projectId) {
        // 1. Check cache
        if (ontologyIriCache.containsKey(projectId)) {
            return ontologyIriCache.get(projectId);
        }

        // 2. Try to find an explicit owl:Ontology declaration
        String query = PREFIXES + "SELECT ?ont WHERE { ?ont a owl:Ontology } LIMIT 1";
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("ont")) {
                    String iri = sol.getValue("ont").stringValue();
                    ontologyIriCache.put(projectId, iri);
                    return iri;
                }
            }
            
            // 3. Try to find something that imports other ontologies
            query = PREFIXES + "SELECT ?ont WHERE { ?ont owl:imports ?any } LIMIT 1";
            rs = datasetService.execSelect(projectId, query);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("ont")) {
                    String iri = sol.getValue("ont").stringValue();
                    ontologyIriCache.put(projectId, iri);
                    return iri;
                }
            }

            // 4. Try to find something with common ontology metadata
            query = PREFIXES + """
                SELECT ?ont WHERE { 
                  ?ont ?p ?o . 
                  FILTER(?p IN (rdfs:label, rdfs:comment, dc:title, dc:creator, owl:versionInfo)) 
                  # Ensure it's an IRI and not a class/property (heuristic)
                  FILTER(isIRI(?ont))
                  FILTER(!EXISTS { ?ont a owl:Class } && !EXISTS { ?ont a owl:ObjectProperty })
                } LIMIT 1
                """;
            rs = datasetService.execSelect(projectId, query);
            if (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("ont")) {
                    String iri = sol.getValue("ont").stringValue();
                    ontologyIriCache.put(projectId, iri);
                    return iri;
                }
            }
        } catch (Exception e) {
            log.error("Error fetching ontology IRI for project " + projectId, e);
        }
        return null;
    }

    private String formatResource(String iri) {
        if (iri == null) return null;
        if (iri.startsWith("_:")) return iri;
        if (iri.contains(":") && !iri.startsWith("http")) return iri; // Prefixed name
        return "<" + iri + ">";
    }

    private String escapeString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
