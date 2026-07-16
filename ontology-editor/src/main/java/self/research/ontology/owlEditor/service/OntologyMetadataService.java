package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.model.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

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

    private static final Pattern AND_SPLIT = Pattern.compile("(?i)\\s+and\\s+");
    private static final Pattern OR_SPLIT = Pattern.compile("(?i)\\s+or\\s+");

    private final SparqlDatasetService datasetService;
    private final ProjectMetadataService projectMetadataService;
    private final OntologyMutationService mutationService;
    private final GeneralClassAxiomService generalClassAxiomService;
    private final Map<String, String> ontologyIriCache = new java.util.concurrent.ConcurrentHashMap<>();

    public OntologyMetadataService(SparqlDatasetService datasetService,
                                   ProjectMetadataService projectMetadataService,
                                   @Lazy OntologyMutationService mutationService,
                                   GeneralClassAxiomService generalClassAxiomService) {
        this.datasetService = datasetService;
        this.projectMetadataService = projectMetadataService;
        this.mutationService = mutationService;
        this.generalClassAxiomService = generalClassAxiomService;
    }

    /**
     * Get all metadata for an ontology
     * Uses cached metadata from MongoDB if available to avoid expensive GraphDB queries
     */
    public Map<String, Object> getMetadata(String projectId) {
        Map<String, Object> metadata = new HashMap<>();

        // The cached metadata below is shared project-wide and only ever reflects the public
        // graph (computed once after each direct/publish mutation). A drafter's own new
        // classes/properties/individuals only exist in their draft graph, so serving them this
        // cache would show stale, pre-draft counts (e.g. "Classes" badge not incrementing after
        // a draft createClass). Skip the cache for drafters and fall through to the live query
        // path below, which is draft-aware via SparqlDatasetService.execSelect's automatic
        // FROM <draftGraph> injection for the current SparqlQueryContext user.
        String ctxUserId = SparqlQueryContext.getUserId();
        boolean hasDraft = ctxUserId != null && datasetService.hasActiveDraftOverlay(projectId, ctxUserId);

        // 1. Check if cached metadata exists and is valid
        Optional<Map<String, Object>> cachedMetadata = hasDraft
                ? Optional.empty() : projectMetadataService.readMeta(projectId);

        if (cachedMetadata.isPresent() && !cachedMetadata.get().isEmpty()) {
            Map<String, Object> cached = cachedMetadata.get();

            // Reject a "complete" zero-entity cache when triples exist — that was typically a
            // failed/partial SPARQL metrics pass that then stuck the Classes badge at 0 forever.
            boolean hasCounts = cached.containsKey("counts") || cached.containsKey("classCount")
                    || cached.containsKey("cacheComplete");
            if (hasCounts && !isUnreliableZeroCache(cached)) {
                log.info("⚡ Using cached metadata for project {} (fast path, skipping GraphDB queries)", projectId);
                metadata.putAll(cached);

                // Always include fresh filename and status from MongoDB
                projectMetadataService.readStatus(projectId).ifPresent(status -> {
                    metadata.put("filename", status.filename());
                    metadata.put("projectStatus", status.status());
                });

                return metadata;
            }
            if (hasCounts) {
                log.warn("♻️ Ignoring unreliable zero-count metadata cache for project {} — recomputing", projectId);
            }
        }

        // 2. Cache miss or incomplete - compute from GraphDB (slow path)
        log.info("📊 Computing fresh metadata for project {} (slow path, querying GraphDB)", projectId);
        
        // Include filename and status from project metadata for UI context
        projectMetadataService.readStatus(projectId).ifPresent(status -> {
            metadata.put("filename", status.filename());
            metadata.put("projectStatus", status.status());
        });
        
        // Merge with dynamic metrics from GraphDB
        metadata.putAll(getDynamicMetrics(projectId));
        
        // Get ontology IRI and version IRI
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
        
        // Add other metadata components
        metadata.put("prefixes", getPrefixes(projectId));
        metadata.put("annotations", getOntologyAnnotations(projectId));
        metadata.put("imports", getOntologyImports(projectId));
        metadata.put("axioms", getGeneralClassAxioms(projectId));
        
        // Save to cache for future fast loading — but never from a drafter's request, or their
        // draft-inclusive counts would leak into the shared cache and be served to everyone else.
        // Also never cache a "complete" zero-count payload when the graph has triples: that usually
        // means SPARQL metrics timed out/failed and would permanently pin the Classes badge at 0.
        if (!hasDraft) {
            try {
                if (isUnreliableZeroCache(metadata) || Boolean.TRUE.equals(metadata.get("metricsFailed"))) {
                    log.warn("⏭️ Skipping metadata cache for project {} — metrics look incomplete "
                                    + "(classCount={}, triples={}, metricsFailed={})",
                            projectId,
                            metadata.get("classCount"),
                            metadata.get("tripleCount") != null ? metadata.get("tripleCount") : metadata.get("axiomCount"),
                            metadata.get("metricsFailed"));
                } else {
                    metadata.put("cacheComplete", true);
                    metadata.put("cachedAt", java.time.Instant.now().toString());
                    projectMetadataService.writeMeta(projectId, new HashMap<>(metadata));
                    log.info("💾 Saved metadata to MongoDB cache for project {}", projectId);
                }
            } catch (Exception e) {
                log.warn("Failed to cache metadata for project {}: {}", projectId, e.getMessage());
            }
        }

        return metadata;
    }

    /** True when entity counts are all zero but the graph clearly has triples (or metricsFailed). */
    private static boolean isUnreliableZeroCache(Map<String, Object> meta) {
        if (Boolean.TRUE.equals(meta.get("metricsFailed"))) {
            return true;
        }
        int classCount = toInt(meta.get("classCount"));
        if (classCount == 0 && meta.get("counts") instanceof Map<?, ?> counts) {
            Object classes = counts.get("classes");
            if (classes instanceof Number) {
                classCount = ((Number) classes).intValue();
            }
        }
        int objectPropertyCount = toInt(meta.get("objectPropertyCount"));
        int dataPropertyCount = toInt(meta.get("dataPropertyCount"));
        int individualCount = toInt(meta.get("individualCount"));
        boolean zeroEntities = classCount == 0 && objectPropertyCount == 0
                && dataPropertyCount == 0 && individualCount == 0;
        if (!zeroEntities) {
            return false;
        }
        long triples = 0;
        Object tripleCountObj = meta.get("tripleCount");
        Object axiomCountObj = meta.get("axiomCount");
        if (tripleCountObj instanceof Number) {
            triples = ((Number) tripleCountObj).longValue();
        } else if (axiomCountObj instanceof Number) {
            triples = ((Number) axiomCountObj).longValue();
        }
        return triples > 0;
    }

    private static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
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
                    String propertyValue = sol.getValue("property").stringValue();
                    ann.put("property", propertyValue);
                    ann.put("propertyIri", propertyValue);
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
        addOntologyAnnotation(projectId, propertyIri, value, language, datatype, false, null);
    }

    public void addOntologyAnnotation(String projectId, String propertyIri, String value, String language, String datatype,
                                      boolean draft, String userId) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) {
            // If no ontology triple exists, create one using a stable IRI based on project ID
            ontologyIri = "http://ontocode.org/resource/ontology/" + projectId;
            String initUpdate = PREFIXES + String.format("INSERT DATA { <%s> a owl:Ontology . }", ontologyIri);
            mutationService.applyRawUpdate(projectId, initUpdate, draft, userId);
            if (!draft) {
                ontologyIriCache.put(projectId, ontologyIri);
            }
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

        mutationService.applyRawUpdate(projectId, update, draft, userId);
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
        updateOntologyAnnotation(projectId, propertyIri, oldValue, newValue, language, datatype, originalPropertyIri,
                false, null);
    }

    public void updateOntologyAnnotation(
                    String projectId,
                    String propertyIri,
                    String oldValue,
                    String newValue,
                    String language,
                    String datatype,
                    String originalPropertyIri,
                    boolean draft,
                    String userId) {
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

        mutationService.applyRawUpdate(projectId, update, draft, userId);
    }

    /**
     * Delete an ontology annotation
     */
    public void deleteOntologyAnnotation(String projectId, String propertyIri, String value, String language) {
        deleteOntologyAnnotation(projectId, propertyIri, value, language, false, null);
    }

    public void deleteOntologyAnnotation(String projectId, String propertyIri, String value, String language,
                                         boolean draft, String userId) {
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

        mutationService.applyRawUpdate(projectId, update, draft, userId);
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
     * Transitive import closure for each direct import of the active ontology.
     * Keys are direct import IRIs; values are nested child import trees.
     */
    public Map<String, List<Map<String, Object>>> getImportClosure(String projectId) {
        Map<String, List<String>> importGraph = loadImportGraph(projectId);
        Map<String, List<Map<String, Object>>> closure = new LinkedHashMap<>();
        for (String directImport : getOntologyImports(projectId)) {
            closure.put(directImport, buildImportSubtree(directImport, importGraph, new HashSet<>()));
        }
        return closure;
    }

    private Map<String, List<String>> loadImportGraph(String projectId) {
        String query = PREFIXES + """
            SELECT ?subject ?import WHERE {
              ?subject owl:imports ?import .
            }
            """;
        Map<String, List<String>> graph = new HashMap<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (!sol.hasBinding("subject") || !sol.hasBinding("import")) {
                    continue;
                }
                String subject = sol.getValue("subject").stringValue();
                String importIri = sol.getValue("import").stringValue();
                graph.computeIfAbsent(subject, ignored -> new ArrayList<>()).add(importIri);
            }
        } catch (Exception e) {
            log.error("Error loading import graph for project {}", projectId, e);
        }
        return graph;
    }

    private List<Map<String, Object>> buildImportSubtree(String iri,
                                                         Map<String, List<String>> graph,
                                                         Set<String> visited) {
        if (!visited.add(iri)) {
            return List.of();
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String childIri : graph.getOrDefault(iri, List.of())) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("iri", childIri);
            node.put("children", buildImportSubtree(childIri, graph, new HashSet<>(visited)));
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * Add an ontology import
     */
    public void addOntologyImport(String projectId, String importIri) {
        addOntologyImport(projectId, importIri, false, null);
    }

    public void addOntologyImport(String projectId, String importIri, boolean draft, String userId) {
        log.info("Adding import '{}' to project {} (draft={})", importIri, projectId, draft);
        
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) {
            // If no ontology triple exists, create one using a stable IRI
            ontologyIri = "http://ontocode.org/resource/ontology/" + projectId;
            String initUpdate = PREFIXES + String.format("INSERT DATA { <%s> a owl:Ontology . }", ontologyIri);
            mutationService.applyRawUpdate(projectId, initUpdate, draft, userId);
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
        mutationService.applyRawUpdate(projectId, update, draft, userId);
        log.info("✅ Successfully added import '{}' to project {}", importIri, projectId);
    }

    /**
     * Delete an ontology import
     */
    public void deleteOntologyImport(String projectId, String importIri) {
        deleteOntologyImport(projectId, importIri, false, null);
    }

    public void deleteOntologyImport(String projectId, String importIri, boolean draft, String userId) {
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

        mutationService.applyRawUpdate(projectId, update, draft, userId);
    }

    /**
     * One-time migration: convert legacy {@code ontocode.org/resource/gci} string literals into real OWL GCIs.
     */
    private void migrateLegacyGciStrings(String projectId) {
        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) return;
        String formattedOntologyIri = formatResource(ontologyIri);
        String query = PREFIXES + String.format("""
            SELECT ?gci WHERE {
              %s <http://ontocode.org/resource/gci> ?gci .
            }
            """, formattedOntologyIri);
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            List<String> legacy = new ArrayList<>();
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("gci")) {
                    legacy.add(sol.getValue("gci").stringValue());
                }
            }
            for (String value : legacy) {
                if (!value.contains(" SubClassOf ")) {
                    deleteLegacyGciLiteral(projectId, formattedOntologyIri, value);
                    continue;
                }
                String[] parts = value.split(" SubClassOf ", 2);
                try {
                    addGCI(projectId, parts[0].trim(), parts[1].trim());
                    deleteLegacyGciLiteral(projectId, formattedOntologyIri, value);
                    log.info("Migrated legacy GCI string to real axiom for project {}", projectId);
                } catch (Exception e) {
                    log.warn("Could not migrate legacy GCI '{}': {}", value, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Legacy GCI migration skipped for {}: {}", projectId, e.getMessage());
        }
    }

    private void deleteLegacyGciLiteral(String projectId, String formattedOntologyIri, String legacyValue) {
        String update = PREFIXES + String.format("""
            DELETE {
              %s <http://ontocode.org/resource/gci> "%s" .
            }
            WHERE {
              %s <http://ontocode.org/resource/gci> "%s" .
            }
            """, formattedOntologyIri, escapeString(legacyValue),
                formattedOntologyIri, escapeString(legacyValue));
        datasetService.execUpdate(projectId, update);
    }

    /**
     * Get all General Class Axioms (GCIs)
     */
    public List<Map<String, Object>> getGeneralClassAxioms(String projectId) {
        migrateLegacyGciStrings(projectId);

        String ontologyIri = getOntologyIri(projectId);
        if (ontologyIri == null) return new ArrayList<>();

        // Real OWL GCIs only: blank-node subjects in SubClassOf axioms
        String query = PREFIXES + """
            SELECT ?sub ?super WHERE {
              ?sub rdfs:subClassOf ?super .
              FILTER(isBlank(?sub))
            }
            """;

        List<Map<String, Object>> gcis = new ArrayList<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, query);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (!sol.hasBinding("sub") || !sol.hasBinding("super")) continue;
                String subId = sol.getValue("sub").stringValue();
                String superId = sol.getValue("super").stringValue();
                Map<String, Object> gci = new LinkedHashMap<>();
                gci.put("id", subId);
                gci.put("subClass", subId);
                gci.put("superClass", superId);
                gci.put("value", subId + " SubClassOf " + superId);
                gcis.add(gci);
            }
        } catch (Exception e) {
            log.error("Error fetching general class axioms for project " + projectId, e);
        }
        return gcis;
    }

    /**
     * Add a General Class Axiom (GCI) as a real OWL blank-node SubClassOf axiom.
     */
    public void addGCI(String projectId, String subClassExpr, String superClassExpr) {
        addGCI(projectId, subClassExpr, superClassExpr, false, null);
    }

    /**
     * Draft-aware variant: when {@code draft} is true, the axiom is written to the user's
     * private draft graph instead of the shared/public ontology.
     */
    public void addGCI(String projectId, String subClassExpr, String superClassExpr, boolean draft, String userId) {
        if (subClassExpr == null || subClassExpr.isBlank()) {
            throw new IllegalArgumentException("GCA sub-class expression is required");
        }
        if (superClassExpr == null || superClassExpr.isBlank()) {
            throw new IllegalArgumentException("GCA super-class is required");
        }

        try {
            generalClassAxiomService.addGeneralClassAxiom(
                    projectId, subClassExpr.trim(), superClassExpr.trim(), draft, userId);
            return;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Full Manchester GCA parse failed, trying simple named intersection/union: {}", e.getMessage());
        }

        String superIri = resolveClassIri(projectId, superClassExpr.trim());
        if (superIri == null || superIri.isBlank()) {
            throw new IllegalArgumentException("Cannot resolve super-class: " + superClassExpr);
        }

        String sub = subClassExpr.trim();
        OntologyMutationService.MutationOp op;
        if (AND_SPLIT.split(sub).length >= 2) {
            String[] parts = AND_SPLIT.split(sub);
            String members = String.join(",", resolveClassIris(projectId, parts));
            op = new OntologyMutationService.MutationOp(
                    "addGCAIntersection", superIri, null, null, null, members,
                    null, null, null, null, null, null, null, null, null);
        } else if (OR_SPLIT.split(sub).length >= 2) {
            String[] parts = OR_SPLIT.split(sub);
            String members = String.join(",", resolveClassIris(projectId, parts));
            op = new OntologyMutationService.MutationOp(
                    "addGCAUnion", superIri, null, null, null, members,
                    null, null, null, null, null, null, null, null, null);
        } else {
            throw new IllegalArgumentException(
                    "GCA could not be parsed. Use Manchester syntax (e.g. 'A and (p some B) SubClassOf C')");
        }
        if (draft) {
            mutationService.applyDraft(projectId, userId, List.of(op));
        } else {
            mutationService.apply(projectId, List.of(op));
        }
    }

    /**
     * Delete a General Class Axiom — supports legacy string literals and real blank-node GCIs.
     */
    public void deleteGCI(String projectId, String gciValue) {
        deleteGCI(projectId, gciValue, false, null);
    }

    /**
     * Draft-aware variant: when {@code draft} is true, the deletion is applied to the user's
     * private draft graph instead of the shared/public ontology.
     */
    public void deleteGCI(String projectId, String gciValue, boolean draft, String userId) {
        if (gciValue == null || gciValue.isBlank()) return;

        // Legacy custom-predicate string storage
        if (gciValue.contains(" SubClassOf ")) {
            String ontologyIri = getOntologyIri(projectId);
            if (ontologyIri != null) {
                String formattedOntologyIri = formatResource(ontologyIri);
                String legacyValue = gciValue.contains(" SubClassOf ")
                        ? gciValue
                        : gciValue;
                String update = PREFIXES + String.format("""
                    DELETE {
                      %s <http://ontocode.org/resource/gci> "%s" .
                    }
                    WHERE {
                      %s <http://ontocode.org/resource/gci> "%s" .
                    }
                    """, formattedOntologyIri, escapeString(legacyValue),
                        formattedOntologyIri, escapeString(legacyValue));
                mutationService.applyRawUpdate(projectId, update, draft, userId);
            }
        }

        // Real blank-node GCI (id is STR(?sub) from queries)
        String blankNodeId = gciValue;
        if (gciValue.contains(" SubClassOf ")) {
            String subPart = gciValue.split(" SubClassOf ", 2)[0].trim();
            if (looksLikeBlankNodeId(subPart)) {
                blankNodeId = subPart;
            }
        }
        if (looksLikeBlankNodeId(blankNodeId)) {
            List<OntologyMutationService.MutationOp> ops = List.of(
                    new OntologyMutationService.MutationOp(
                            "deleteAxiom", blankNodeId, null, null, null, null,
                            null, null, null, null, null, null, null, null, null));
            if (draft) {
                mutationService.applyDraft(projectId, userId, ops);
            } else {
                mutationService.apply(projectId, ops);
            }
        }
    }

    private boolean looksLikeBlankNodeId(String value) {
        if (value == null || value.isBlank()) return false;
        return value.startsWith("_:")
                || value.contains("/genid/")
                || value.contains("/.well-known/genid/");
    }

    private String resolveClassIri(String projectId, String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.startsWith("http") || trimmed.startsWith("urn:")) return trimmed;
        if (trimmed.contains(":") && !trimmed.contains(" ")) {
            if (trimmed.startsWith("owl:")) {
                return "http://www.w3.org/2002/07/owl#" + trimmed.substring(4);
            }
            if (trimmed.startsWith("rdfs:")) {
                return "http://www.w3.org/2000/01/rdf-schema#" + trimmed.substring(5);
            }
        }
        String escaped = trimmed.replace("\"", "\\\"");
        String query = PREFIXES + """
            SELECT ?iri WHERE {
              { ?iri rdfs:label "%s" }
              UNION
              { ?iri rdfs:label ?lbl . FILTER(LCASE(STR(?lbl)) = LCASE("%s")) }
            } LIMIT 1
            """.formatted(escaped, escaped);
        try {
            TupleQueryResult result = datasetService.execSelect(projectId, query);
            if (result.hasNext()) {
                return result.next().getValue("iri").stringValue();
            }
        } catch (Exception e) {
            log.warn("Failed to resolve class '{}': {}", trimmed, e.getMessage());
        }
        return trimmed;
    }

    private List<String> resolveClassIris(String projectId, String[] parts) {
        List<String> iris = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            iris.add(resolveClassIri(projectId, part.trim()));
        }
        return iris;
    }

    /**
     * Update ontology IRI and version IRI
     */
    public void updateOntologyIRIs(String projectId, String newOntologyIri, String newVersionIri) {
        updateOntologyIRIs(projectId, newOntologyIri, newVersionIri, false, null);
    }

    public void updateOntologyIRIs(String projectId, String newOntologyIri, String newVersionIri,
                                   boolean draft, String userId) {
        String oldOntologyIri = getOntologyIri(projectId);
        String formattedOld = oldOntologyIri != null ? formatResource(oldOntologyIri) : null;
        String formattedNew = formatResource(newOntologyIri);

        StringBuilder update = new StringBuilder(PREFIXES);
        if (formattedOld != null) {
            // Move ALL triples from the old IRI to the new IRI (preserves annotations, imports, etc.)
            update.append("DELETE { ").append(formattedOld).append(" ?p ?o } ");
            update.append("INSERT { ").append(formattedNew).append(" ?p ?o } ");
            update.append("WHERE  { ").append(formattedOld).append(" ?p ?o } ;");
        }
        // Ensure the new IRI is declared as owl:Ontology (in case old IRI had no triples)
        update.append("\nINSERT DATA { ").append(formattedNew).append(" a owl:Ontology . } ;");
        if (newVersionIri != null && !newVersionIri.isEmpty()) {
            // Replace any existing versionIRI with the supplied one
            update.append("\nDELETE { ").append(formattedNew).append(" owl:versionIRI ?v } ");
            update.append("WHERE  { ").append(formattedNew).append(" owl:versionIRI ?v } ;");
            update.append("\nINSERT DATA { ").append(formattedNew).append(" owl:versionIRI <").append(newVersionIri).append("> . }");
        }

        mutationService.applyRawUpdate(projectId, update.toString(), draft, userId);

        // The IRI cache and shared Mongo metadata reflect the PUBLIC graph only — a draft
        // IRI change must not leak into what other users (or this user's public view) see.
        if (!draft) {
            ontologyIriCache.put(projectId, newOntologyIri);
            projectMetadataService.readMeta(projectId).ifPresent(cached -> {
                Map<String, Object> meta = new HashMap<>(cached);
                meta.put("ontologyIRI", newOntologyIri);
                if (newVersionIri != null && !newVersionIri.isEmpty()) {
                    meta.put("versionIRI", newVersionIri);
                } else {
                    meta.remove("versionIRI");
                }
                projectMetadataService.writeMeta(projectId, meta);
            });
        }
    }

    /**
     * Get dynamic metrics from GraphDB
     */
    public Map<String, Object> getDynamicMetrics(String projectId) {
        Map<String, Object> metrics = new HashMap<>();
        
        log.info("Calculating dynamic metrics for project: {}", projectId);

        // ── 1. Entity type counts (single query) ──
        String typeCounts = PREFIXES + """
            SELECT ?type (COUNT(DISTINCT ?s) AS ?count) WHERE {
              ?s a ?type .
              VALUES ?type {
                owl:Class owl:ObjectProperty owl:DatatypeProperty
                owl:AnnotationProperty owl:NamedIndividual
                owl:FunctionalProperty owl:InverseFunctionalProperty
                owl:TransitiveProperty owl:SymmetricProperty
                owl:AsymmetricProperty owl:ReflexiveProperty
                owl:IrreflexiveProperty owl:NegativePropertyAssertion
              }
            }
            GROUP BY ?type
            """;
        Map<String, Integer> typeCountMap = new HashMap<>();
        boolean typeCountsFailed = false;
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, typeCounts);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("type") && sol.hasBinding("count")) {
                    typeCountMap.put(sol.getValue("type").stringValue(),
                            Integer.parseInt(sol.getValue("count").stringValue()));
                }
            }
        } catch (Exception e) {
            typeCountsFailed = true;
            log.error("Error getting type counts for project {}", projectId, e);
        }

        int classCount = typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#Class", 0);
        int objectPropertyCount = typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#ObjectProperty", 0);
        int dataPropertyCount = typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#DatatypeProperty", 0);
        int annotationPropertyCount = typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#AnnotationProperty", 0);
        int individualCount = typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#NamedIndividual", 0);

        metrics.put("classCount", classCount);
        metrics.put("objectPropertyCount", objectPropertyCount);
        metrics.put("dataPropertyCount", dataPropertyCount);
        metrics.put("annotationPropertyCount", annotationPropertyCount);
        metrics.put("individualCount", individualCount);
        if (typeCountsFailed) {
            metrics.put("metricsFailed", true);
        }

        int tripleCount = (int) datasetService.getDatasetSize(projectId);
        metrics.put("axiomCount", tripleCount);
        metrics.put("tripleCount", tripleCount);
        int declCount = classCount + objectPropertyCount + dataPropertyCount + annotationPropertyCount + individualCount;
        metrics.put("declarationAxiomCount", declCount);
        metrics.put("logicalAxiomCount", Math.max(0, tripleCount - declCount));

        metrics.put("functionalObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#FunctionalProperty", 0));
        metrics.put("inverseFunctionalObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#InverseFunctionalProperty", 0));
        metrics.put("transitiveObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#TransitiveProperty", 0));
        metrics.put("symmetricObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#SymmetricProperty", 0));
        metrics.put("asymmetricObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#AsymmetricProperty", 0));
        metrics.put("reflexiveObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#ReflexiveProperty", 0));
        metrics.put("irreflexiveObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#IrreflexiveProperty", 0));
        metrics.put("negativeObjectPropertyAssertionAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#NegativePropertyAssertion", 0));
        metrics.put("negativeDataPropertyAssertionAxiomCount", 0);
        // Reuse functional count for data properties (shared OWL type)
        metrics.put("functionalDataPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#FunctionalProperty", 0));

        // ── 2. Predicate counts (single query) ──
        String predicateCounts = PREFIXES + """
            SELECT ?pred (COUNT(*) AS ?count) WHERE {
              ?s ?pred ?o .
              VALUES ?pred {
                rdfs:subClassOf owl:equivalentClass owl:disjointWith
                rdfs:subPropertyOf owl:equivalentProperty owl:inverseOf
                owl:propertyDisjointWith rdfs:domain rdfs:range
                rdf:type owl:sameAs owl:differentFrom owl:propertyChainAxiom
              }
            }
            GROUP BY ?pred
            """;
        Map<String, Integer> predCountMap = new HashMap<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, predicateCounts);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("pred") && sol.hasBinding("count")) {
                    predCountMap.put(sol.getValue("pred").stringValue(),
                            Integer.parseInt(sol.getValue("count").stringValue()));
                }
            }
        } catch (Exception e) {
            log.error("Error getting predicate counts for project {}", projectId, e);
        }

        int domainCount = predCountMap.getOrDefault("http://www.w3.org/2000/01/rdf-schema#domain", 0);
        int rangeCount = predCountMap.getOrDefault("http://www.w3.org/2000/01/rdf-schema#range", 0);
        int subPropCount = predCountMap.getOrDefault("http://www.w3.org/2000/01/rdf-schema#subPropertyOf", 0);
        int equivPropCount = predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#equivalentProperty", 0);
        int disjPropCount = predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#propertyDisjointWith", 0);

        metrics.put("subClassOfAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2000/01/rdf-schema#subClassOf", 0));
        metrics.put("equivalentClassesAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#equivalentClass", 0));
        metrics.put("disjointClassesAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#disjointWith", 0));
        metrics.put("subObjectPropertyOfAxiomCount", subPropCount);
        metrics.put("equivalentObjectPropertiesAxiomCount", equivPropCount);
        metrics.put("inverseObjectPropertiesAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#inverseOf", 0));
        metrics.put("disjointObjectPropertiesAxiomCount", disjPropCount);
        metrics.put("objectPropertyDomainAxiomCount", domainCount);
        metrics.put("objectPropertyRangeAxiomCount", rangeCount);
        metrics.put("subDataPropertyOfAxiomCount", subPropCount);
        metrics.put("equivalentDataPropertiesAxiomCount", equivPropCount);
        metrics.put("disjointDataPropertiesAxiomCount", disjPropCount);
        metrics.put("dataPropertyDomainAxiomCount", domainCount);
        metrics.put("dataPropertyRangeAxiomCount", rangeCount);
        metrics.put("classAssertionAxiomCount", predCountMap.getOrDefault("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", 0));
        metrics.put("sameIndividualAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#sameAs", 0));
        metrics.put("differentIndividualsAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#differentFrom", 0));
        metrics.put("subPropertyChainOfAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#propertyChainAxiom", 0));
        metrics.put("annotationPropertyDomainAxiomCount", domainCount);
        metrics.put("annotationPropertyRangeAxiomCount", rangeCount);
        metrics.put("subAnnotationPropertyOfAxiomCount", subPropCount);

        // ── 3. Predicate-type join counts (single query) ──
        String predTypeCounts = PREFIXES + """
            SELECT ?ptype (COUNT(*) AS ?count) WHERE {
              ?s ?p ?o .
              ?p a ?ptype .
              VALUES ?ptype { owl:ObjectProperty owl:DatatypeProperty owl:AnnotationProperty }
            }
            GROUP BY ?ptype
            """;
        Map<String, Integer> predTypeMap = new HashMap<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, predTypeCounts);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("ptype") && sol.hasBinding("count")) {
                    predTypeMap.put(sol.getValue("ptype").stringValue(),
                            Integer.parseInt(sol.getValue("count").stringValue()));
                }
            }
        } catch (Exception e) {
            log.error("Error getting predicate-type counts for project {}", projectId, e);
        }

        metrics.put("objectPropertyAssertionAxiomCount", predTypeMap.getOrDefault("http://www.w3.org/2002/07/owl#ObjectProperty", 0));
        metrics.put("dataPropertyAssertionAxiomCount", predTypeMap.getOrDefault("http://www.w3.org/2002/07/owl#DatatypeProperty", 0));
        metrics.put("annotationAssertionAxiomCount", predTypeMap.getOrDefault("http://www.w3.org/2002/07/owl#AnnotationProperty", 0));

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
        boolean hasCachedPrefixes = false;
        Optional<Map<String, Object>> meta = projectMetadataService.readMeta(projectId);
        if (meta.isPresent() && meta.get().containsKey("prefixes")) {
            Object prefixesObj = meta.get().get("prefixes");
            if (prefixesObj instanceof Map) {
                Map<?, ?> rawMap = (Map<?, ?>) prefixesObj;
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    prefixMap.put(entry.getKey().toString(), entry.getValue().toString());
                }
                hasCachedPrefixes = !prefixMap.isEmpty();
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
                hasCachedPrefixes = !prefixMap.isEmpty();
            }
        }

        // 2. Only query GraphDB if MongoDB has no cached prefixes.
        //    All prefix mutations (add/update/delete) already update MongoDB,
        //    so cached prefixes are always in sync. Skipping the expensive
        //    SPARQL namespace-usage query avoids running it twice during import.
        if (!hasCachedPrefixes) {
            log.debug("No cached prefixes in MongoDB, querying GraphDB for project {}", projectId);
            Map<String, String> graphdbPrefixes = datasetService.getPrefixes(projectId);
            prefixMap.putAll(graphdbPrefixes);
        } else {
            log.debug("Using cached prefixes from MongoDB for project {} ({} entries)", projectId, prefixMap.size());
        }

        // Always include the standard OWL/RDF/RDFS/XSD prefixes (like Protégé shows by default).
        // User-defined prefixes take precedence; we only add a standard one if not already present.
        Map<String, String> defaults = new java.util.LinkedHashMap<>();
        defaults.put("owl",   "http://www.w3.org/2002/07/owl#");
        defaults.put("rdf",   "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        defaults.put("rdfs",  "http://www.w3.org/2000/01/rdf-schema#");
        defaults.put("xsd",   "http://www.w3.org/2001/XMLSchema#");
        defaults.put("dc",    "http://purl.org/dc/elements/1.1/");
        defaults.put("dcterms", "http://purl.org/dc/terms/");
        defaults.put("skos",  "http://www.w3.org/2004/02/skos/core#");
        for (Map.Entry<String, String> e : defaults.entrySet()) {
            prefixMap.putIfAbsent(e.getKey(), e.getValue());
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
        if (iri.startsWith("<")) return iri;
        if (iri.startsWith("_:")) return iri;
        // A genuine SPARQL prefixed name (owl:Class, rdfs:label) has no ':', '/', or '#' after the colon.
        // Full URIs with non-http schemes (urn:, file:, urn:uuid:) must be wrapped in < >.
        int colonIdx = iri.indexOf(':');
        if (colonIdx > 0) {
            String localPart = iri.substring(colonIdx + 1);
            if (!localPart.contains(":") && !localPart.contains("/") && !localPart.contains("#")) {
                return iri; // genuine CURIE like owl:Class
            }
        }
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
