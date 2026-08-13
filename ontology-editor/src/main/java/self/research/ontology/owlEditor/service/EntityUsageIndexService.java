package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.EntityUsageDocument;
import self.research.ontology.owlEditor.repository.EntityUsageRepository;

import java.util.*;

@Slf4j
@Service
public class EntityUsageIndexService {

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        """;

    private final EntityUsageRepository repo;
    private final SparqlDatasetService datasetService;
    private final MainGraphRevisionService revisionService;

    public EntityUsageIndexService(EntityUsageRepository repo,
                                   SparqlDatasetService datasetService,
                                   MainGraphRevisionService revisionService) {
        this.repo = repo;
        this.datasetService = datasetService;
        this.revisionService = revisionService;
    }

    public Optional<List<Map<String, String>>> getUsage(String projectId, String entityIri) {
        return repo.findByProjectIdAndEntityIri(projectId, entityIri)
                .map(EntityUsageDocument::getUsages);
    }

    public void invalidate(String projectId, List<String> affectedIris) {
        if (affectedIris == null || affectedIris.isEmpty()) return;
        try {
            repo.deleteByProjectIdAndEntityIriIn(projectId, affectedIris);
            log.debug("[UsageIndex] Invalidated {} entries for project {}", affectedIris.size(), projectId);
        } catch (Exception e) {
            log.warn("[UsageIndex] Invalidation failed for project {}: {}", projectId, e.getMessage());
        }
    }

    public void dropAll(String projectId) {
        try {
            repo.deleteByProjectId(projectId);
            log.info("[UsageIndex] Dropped all usage entries for project {}", projectId);
        } catch (Exception e) {
            log.warn("[UsageIndex] Drop failed for project {}: {}", projectId, e.getMessage());
        }
    }

    @Async("metadataExecutor")
    public void scheduleBuild(String projectId) {
        try {
            log.info("[UsageIndex] Starting batch usage index build for project {}", projectId);
            long start = System.currentTimeMillis();
            long revision = revisionService.getRevision(projectId);

            Map<String, List<Map<String, String>>> byEntity = new LinkedHashMap<>();

            buildSubclassUsages(projectId, byEntity);
            buildRestrictionUsages(projectId, byEntity);
            buildEquivalentUsages(projectId, byEntity);
            buildDisjointUsages(projectId, byEntity);
            buildDomainRangeUsages(projectId, byEntity);
            buildInstanceUsages(projectId, byEntity);
            buildAnnotationRefUsages(projectId, byEntity);

            List<EntityUsageDocument> docs = new ArrayList<>();
            for (Map.Entry<String, List<Map<String, String>>> entry : byEntity.entrySet()) {
                docs.add(new EntityUsageDocument(projectId, entry.getKey(), revision, entry.getValue()));
            }
            repo.saveAll(docs);

            log.info("[UsageIndex] Built {} entity usage entries for project {} in {}ms",
                    docs.size(), projectId, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[UsageIndex] Build failed for project {}: {}", projectId, e.getMessage(), e);
        }
    }

    private void buildSubclassUsages(String projectId, Map<String, List<Map<String, String>>> out) {
        String q = PREFIXES + """
            SELECT DISTINCT ?parent ?child ?childLabel WHERE {
              ?child rdfs:subClassOf ?parent .
              FILTER(isIRI(?parent) && isIRI(?child))
              OPTIONAL { ?child rdfs:label ?childLabel }
            }
            """;
        try (TupleQueryResult rs = datasetService.execSelect(projectId, q)) {
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String parent = iri(sol, "parent");
                String child = iri(sol, "child");
                if (parent == null || child == null) continue;

                out.computeIfAbsent(parent, k -> new ArrayList<>())
                   .add(entry("subclass", child, label(sol, "childLabel", child), "SubClassOf this"));

                out.computeIfAbsent(child, k -> new ArrayList<>())
                   .add(entry("superclass", parent, localName(parent), "SuperClassOf"));
            }
        } catch (Exception e) {
            log.warn("[UsageIndex] subclass batch failed: {}", e.getMessage());
        }
    }

    private void buildRestrictionUsages(String projectId, Map<String, List<Map<String, String>>> out) {

        String q = PREFIXES + """
            SELECT DISTINCT ?entity ?ownerClass ?ownerLabel ?onProp ?propLabel ?rtype WHERE {
              {
                ?r owl:someValuesFrom ?entity ; owl:onProperty ?onProp .
                BIND("some" AS ?rtype)
              } UNION {
                ?r owl:allValuesFrom ?entity ; owl:onProperty ?onProp .
                BIND("all" AS ?rtype)
              } UNION {
                ?r owl:onClass ?entity ; owl:onProperty ?onProp .
                BIND("qualified" AS ?rtype)
              } UNION {
                ?r owl:hasValue ?entity ; owl:onProperty ?onProp .
                BIND("value" AS ?rtype)
              }
              FILTER(isIRI(?entity))
              {
                ?ownerClass rdfs:subClassOf ?r .
                FILTER(isIRI(?ownerClass))
              } UNION {
                ?ownerClass owl:equivalentClass ?r .
                FILTER(isIRI(?ownerClass))
              } UNION {
                ?container owl:intersectionOf|owl:unionOf ?list .
                ?list rdf:rest*/rdf:first ?r .
                { ?ownerClass rdfs:subClassOf ?container . FILTER(isIRI(?ownerClass)) }
                UNION
                { ?ownerClass owl:equivalentClass ?container . FILTER(isIRI(?ownerClass)) }
              }
              OPTIONAL { ?onProp rdfs:label ?propLabel }
              OPTIONAL { ?ownerClass rdfs:label ?ownerLabel }
            }
            LIMIT 200000
            """;
        try (TupleQueryResult rs = datasetService.execSelect(projectId, q)) {
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String entity = iri(sol, "entity");
                String owner = iri(sol, "ownerClass");
                String onProp = iri(sol, "onProp");
                if (entity == null || owner == null || onProp == null) continue;
                String propLabel = label(sol, "propLabel", onProp);
                String rtype = label(sol, "rtype", "some");
                String ownerLabel = label(sol, "ownerLabel", owner);
                String context = "SubClassOf " + propLabel + " " + rtype + " <this>";
                out.computeIfAbsent(entity, k -> new ArrayList<>())
                   .add(entry("restriction", owner, ownerLabel, context));
            }
        } catch (Exception e) {
            log.warn("[UsageIndex] restriction batch failed: {}", e.getMessage());
        }
    }

    private void buildEquivalentUsages(String projectId, Map<String, List<Map<String, String>>> out) {
        String q = PREFIXES + """
            SELECT DISTINCT ?a ?b ?bLabel WHERE {
              ?a owl:equivalentClass ?b .
              FILTER(isIRI(?a) && isIRI(?b) && ?a != ?b)
              OPTIONAL { ?b rdfs:label ?bLabel }
            }
            """;
        try (TupleQueryResult rs = datasetService.execSelect(projectId, q)) {
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String a = iri(sol, "a");
                String b = iri(sol, "b");
                if (a == null || b == null) continue;
                out.computeIfAbsent(a, k -> new ArrayList<>())
                   .add(entry("equivalent", b, label(sol, "bLabel", b), "EquivalentClass"));
                out.computeIfAbsent(b, k -> new ArrayList<>())
                   .add(entry("equivalent", a, localName(a), "EquivalentClass"));
            }
        } catch (Exception e) {
            log.warn("[UsageIndex] equivalent batch failed: {}", e.getMessage());
        }
    }

    private void buildDisjointUsages(String projectId, Map<String, List<Map<String, String>>> out) {
        String q = PREFIXES + """
            SELECT DISTINCT ?a ?b ?bLabel WHERE {
              ?a owl:disjointWith ?b .
              FILTER(isIRI(?a) && isIRI(?b))
              OPTIONAL { ?b rdfs:label ?bLabel }
            }
            """;
        try (TupleQueryResult rs = datasetService.execSelect(projectId, q)) {
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String a = iri(sol, "a");
                String b = iri(sol, "b");
                if (a == null || b == null) continue;
                out.computeIfAbsent(a, k -> new ArrayList<>())
                   .add(entry("disjoint", b, label(sol, "bLabel", b), "DisjointWith"));
                out.computeIfAbsent(b, k -> new ArrayList<>())
                   .add(entry("disjoint", a, localName(a), "DisjointWith"));
            }
        } catch (Exception e) {
            log.warn("[UsageIndex] disjoint batch failed: {}", e.getMessage());
        }
    }

    private void buildDomainRangeUsages(String projectId, Map<String, List<Map<String, String>>> out) {
        String q = PREFIXES + """
            SELECT DISTINCT ?cls ?prop ?propLabel ?role WHERE {
              { ?prop rdfs:domain ?cls . BIND("domain" AS ?role) }
              UNION
              { ?prop rdfs:range ?cls . BIND("range" AS ?role) }
              FILTER(isIRI(?cls))
              OPTIONAL { ?prop rdfs:label ?propLabel }
            }
            """;
        try (TupleQueryResult rs = datasetService.execSelect(projectId, q)) {
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String cls = iri(sol, "cls");
                String prop = iri(sol, "prop");
                String role = label(sol, "role", "domain");
                if (cls == null || prop == null) continue;
                String propLabel = label(sol, "propLabel", prop);
                out.computeIfAbsent(cls, k -> new ArrayList<>())
                   .add(entry(role, prop, propLabel, role.equals("domain") ? "Domain of property" : "Range of property"));
            }
        } catch (Exception e) {
            log.warn("[UsageIndex] domain/range batch failed: {}", e.getMessage());
        }
    }

    private void buildInstanceUsages(String projectId, Map<String, List<Map<String, String>>> out) {
        String q = PREFIXES + """
            SELECT DISTINCT ?cls ?ind ?indLabel WHERE {
              ?ind a ?cls .
              FILTER(isIRI(?cls) && isIRI(?ind))
              FILTER(?cls != owl:NamedIndividual && ?cls != owl:Class)
              OPTIONAL { ?ind rdfs:label ?indLabel }
            }
            LIMIT 100000
            """;
        try (TupleQueryResult rs = datasetService.execSelect(projectId, q)) {
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String cls = iri(sol, "cls");
                String ind = iri(sol, "ind");
                if (cls == null || ind == null) continue;
                out.computeIfAbsent(cls, k -> new ArrayList<>())
                   .add(entry("instance", ind, label(sol, "indLabel", ind), "Individual of this class"));
            }
        } catch (Exception e) {
            log.warn("[UsageIndex] instance batch failed: {}", e.getMessage());
        }
    }

    private void buildAnnotationRefUsages(String projectId, Map<String, List<Map<String, String>>> out) {

        String q = PREFIXES + """
            SELECT DISTINCT ?entity ?subject ?subjectLabel ?prop ?propLabel WHERE {
              ?subject ?prop ?entity .
              FILTER(isIRI(?entity) && isIRI(?subject))
              { ?prop a owl:AnnotationProperty . }
              UNION { VALUES ?prop { rdfs:seeAlso rdfs:isDefinedBy } }
              OPTIONAL { ?subject rdfs:label ?subjectLabel }
              OPTIONAL { ?prop rdfs:label ?propLabel }
            }
            LIMIT 100000
            """;
        try (TupleQueryResult rs = datasetService.execSelect(projectId, q)) {
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                String entity = iri(sol, "entity");
                String subject = iri(sol, "subject");
                String prop = iri(sol, "prop");
                if (entity == null || subject == null || prop == null) continue;
                String propLabel = label(sol, "propLabel", prop);
                out.computeIfAbsent(entity, k -> new ArrayList<>())
                   .add(entry("annotation", subject, label(sol, "subjectLabel", subject), "Annotation: " + propLabel));
            }
        } catch (Exception e) {
            log.warn("[UsageIndex] annotation ref batch failed: {}", e.getMessage());
        }
    }

    private Map<String, String> entry(String type, String subject, String subjectLabel, String context) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("subject", subject);
        m.put("subjectLabel", subjectLabel);
        m.put("context", context);
        return m;
    }

    private String iri(BindingSet sol, String var) {
        if (!sol.hasBinding(var)) return null;
        org.eclipse.rdf4j.model.Value v = sol.getValue(var);
        if (v instanceof org.eclipse.rdf4j.model.IRI i) return i.toString();
        return null;
    }

    private String label(BindingSet sol, String var, String fallbackIri) {
        if (sol.hasBinding(var)) {
            org.eclipse.rdf4j.model.Value v = sol.getValue(var);
            if (v != null && !v.stringValue().isBlank()) return v.stringValue();
        }
        return localName(fallbackIri);
    }

    private String localName(String iri) {
        if (iri == null) return "";
        int slash = iri.lastIndexOf('/');
        int hash = iri.lastIndexOf('#');
        int idx = Math.max(slash, hash);
        return idx >= 0 && idx < iri.length() - 1 ? iri.substring(idx + 1) : iri;
    }
}
