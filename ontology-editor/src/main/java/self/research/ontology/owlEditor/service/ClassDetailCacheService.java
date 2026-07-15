package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.ClassDetailDocument;
import self.research.ontology.owlEditor.repository.ClassDetailRepository;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MongoDB-backed cache for class details and annotations (replaces Caffeine).
 *
 * Two document kinds stored in the same collection:
 *   partial=true  → annotations-only {id, label, annotations}; satisfies /classes/annotations
 *   partial=false → full classDetails map; satisfies both /classes/details and /classes/annotations
 *
 * Population strategy:
 *   - Import: batch SPARQL builds partial (annotations-only) docs for ALL classes up front.
 *   - First /classes/details hit: SPARQL → stores full doc (partial=false), overwrites any partial.
 *   - First /classes/annotations hit: SPARQL → stores partial doc only if nothing exists yet.
 *
 * Invalidation: per-IRI delete on mutation, full drop on re-import.
 */
@Slf4j
@Service
public class ClassDetailCacheService {

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        """;

    private final ClassDetailRepository repo;
    private final SparqlDatasetService datasetService;

    public ClassDetailCacheService(ClassDetailRepository repo, SparqlDatasetService datasetService) {
        this.repo = repo;
        this.datasetService = datasetService;
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    /**
     * Full class details — returns empty on cache miss OR when only a partial (annotations-only)
     * document is stored. Caller must fetch from SPARQL and call {@link #putDetails}.
     */
    public Optional<Map<String, Object>> getDetails(String projectId, String classIri) {
        return repo.findByProjectIdAndClassIri(projectId, classIri)
                .filter(doc -> !doc.isPartial())
                .map(ClassDetailDocument::getDetails);
    }

    /**
     * Annotations-only fast-path — returns present for BOTH partial and full documents.
     * Extracts {id, label, annotations} from the stored details map.
     */
    public Optional<Map<String, Object>> getAnnotations(String projectId, String classIri) {
        return repo.findByProjectIdAndClassIri(projectId, classIri)
                .map(doc -> extractAnnotations(classIri, doc.getDetails()));
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    /** Store full class details (partial=false). Always overwrites — even a partial doc. */
    public void putDetails(String projectId, String classIri, Map<String, Object> details) {
        try {
            repo.save(new ClassDetailDocument(projectId, classIri, details, false));
        } catch (Exception e) {
            log.warn("[ClassDetailCache] putDetails failed for {}/{}: {}", projectId, classIri, e.getMessage());
        }
    }

    /**
     * Store annotations-only (partial=true) only if no document exists yet.
     * Never downgrades a full (partial=false) doc back to partial.
     */
    public void putAnnotationsIfAbsent(String projectId, String classIri, Map<String, Object> annotationData) {
        try {
            if (repo.findByProjectIdAndClassIri(projectId, classIri).isEmpty()) {
                repo.save(new ClassDetailDocument(projectId, classIri, annotationData, true));
            }
        } catch (Exception e) {
            log.warn("[ClassDetailCache] putAnnotations failed for {}/{}: {}", projectId, classIri, e.getMessage());
        }
    }

    // ── Invalidation ──────────────────────────────────────────────────────────

    public void invalidate(String projectId, List<String> classIris) {
        if (classIris == null || classIris.isEmpty()) return;
        try {
            repo.deleteByProjectIdAndClassIriIn(projectId, classIris);
            log.debug("[ClassDetailCache] Invalidated {} entries for project {}", classIris.size(), projectId);
        } catch (Exception e) {
            log.warn("[ClassDetailCache] Invalidation failed for project {}: {}", projectId, e.getMessage());
        }
    }

    public void dropAll(String projectId) {
        try {
            repo.deleteByProjectId(projectId);
            log.info("[ClassDetailCache] Dropped all class detail entries for project {}", projectId);
        } catch (Exception e) {
            log.warn("[ClassDetailCache] Drop failed for project {}: {}", projectId, e.getMessage());
        }
    }

    // ── Pre-warm ──────────────────────────────────────────────────────────────

    /**
     * Batch-build partial (annotations-only) docs for all named classes in one SPARQL query.
     * Runs async after import so the first /classes/annotations hit for any class is instant.
     *
     * Full classDetails docs are populated lazily on first /classes/details access.
     * For Mondo (~22K classes, ~200K annotation triples): typically < 60 seconds.
     */
    @Async("metadataExecutor")
    public CompletableFuture<Void> scheduleBuildAnnotations(String projectId) {
        try {
            log.info("[ClassDetailCache] Starting batch annotation pre-warm for project {}", projectId);
            long start = System.currentTimeMillis();

            // Collect all annotation assertions grouped by class IRI.
            // Single query — Fuseki returns all in one pass.
            String q = PREFIXES + """
                SELECT ?cls ?clsLabel ?prop ?value WHERE {
                  ?cls a owl:Class .
                  FILTER(isIRI(?cls))
                  ?cls ?prop ?value .
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
                  OPTIONAL { ?cls rdfs:label ?clsLabel }
                }
                """;

            // Group by class IRI: classIri → { prop → [values...] }
            Map<String, Map<String, List<String>>> byClass = new LinkedHashMap<>();
            Map<String, String> classLabels = new LinkedHashMap<>();

            try (TupleQueryResult rs = datasetService.execSelect(projectId, q)) {
                while (rs.hasNext()) {
                    BindingSet sol = rs.next();
                    org.eclipse.rdf4j.model.Value clsVal = sol.getValue("cls");
                    if (!(clsVal instanceof org.eclipse.rdf4j.model.IRI)) continue;
                    String cls = clsVal.toString();

                    org.eclipse.rdf4j.model.Value propVal = sol.getValue("prop");
                    org.eclipse.rdf4j.model.Value valueVal = sol.getValue("value");
                    if (propVal == null || valueVal == null) continue;

                    String prop = propVal.toString();
                    String value = valueVal.stringValue();

                    byClass.computeIfAbsent(cls, k -> new LinkedHashMap<>())
                           .computeIfAbsent(prop, k -> new ArrayList<>())
                           .add(value);

                    // Track rdfs:label for the class
                    if (prop.equals("http://www.w3.org/2000/01/rdf-schema#label")
                            && !classLabels.containsKey(cls)) {
                        classLabels.put(cls, value);
                    }
                    if (sol.hasBinding("clsLabel") && !classLabels.containsKey(cls)) {
                        classLabels.put(cls, sol.getValue("clsLabel").stringValue());
                    }
                }
            }

            // Build and save partial docs
            List<ClassDetailDocument> docs = new ArrayList<>();
            for (Map.Entry<String, Map<String, List<String>>> entry : byClass.entrySet()) {
                String cls = entry.getKey();
                Map<String, List<String>> rawAnnotations = entry.getValue();

                // Flatten single-value annotations (match classAnnotations SPARQL shape)
                Map<String, Object> flatAnnotations = new LinkedHashMap<>();
                rawAnnotations.forEach((prop, values) -> {
                    if (values.size() == 1) flatAnnotations.put(prop, values.get(0));
                    else flatAnnotations.put(prop, values);
                });

                String label = classLabels.getOrDefault(cls,
                        cls.contains("#") ? cls.substring(cls.lastIndexOf('#') + 1)
                                          : cls.substring(cls.lastIndexOf('/') + 1));

                Map<String, Object> partialDetails = new LinkedHashMap<>();
                partialDetails.put("id", cls);
                partialDetails.put("label", label);
                partialDetails.put("annotations", flatAnnotations);

                docs.add(new ClassDetailDocument(projectId, cls, partialDetails, true));
            }

            // Bulk upsert — only insert where nothing exists; don't overwrite full docs
            int stored = 0;
            for (ClassDetailDocument doc : docs) {
                try {
                    boolean exists = repo.findByProjectIdAndClassIri(projectId, doc.getClassIri()).isPresent();
                    if (!exists) {
                        repo.save(doc);
                        stored++;
                    }
                } catch (Exception e) {
                    log.debug("[ClassDetailCache] Skip {} — already exists", doc.getClassIri());
                }
            }

            log.info("[ClassDetailCache] Pre-warmed {} annotation docs for project {} in {}ms",
                    stored, projectId, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[ClassDetailCache] Annotation pre-warm failed for project {}: {}", projectId, e.getMessage(), e);
        }
        return CompletableFuture.completedFuture(null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractAnnotations(String classIri, Map<String, Object> details) {
        if (details == null) return Map.of("id", classIri, "label", "", "annotations", Map.of());
        return Map.of(
            "id",          details.getOrDefault("id", classIri),
            "label",       details.getOrDefault("label", ""),
            "annotations", details.getOrDefault("annotations", Map.of())
        );
    }
}
