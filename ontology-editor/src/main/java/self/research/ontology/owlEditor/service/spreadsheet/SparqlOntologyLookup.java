package self.research.ontology.owlEditor.service.spreadsheet;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.spreadsheet.rule.EntityKind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SparqlOntologyLookup implements OntologyLookup {

    private static final Logger log = LoggerFactory.getLogger(SparqlOntologyLookup.class);

    private static final String OWL = "http://www.w3.org/2002/07/owl#";
    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final String PREFIXES = "PREFIX owl: <" + OWL + ">\nPREFIX rdf: <" + RDF + ">\nPREFIX rdfs: <" + RDFS + ">\n";

    private static final Map<String, EntityKind> TYPE_KINDS = Map.of(
        OWL + "Class", EntityKind.CLASS,
        RDFS + "Class", EntityKind.CLASS,
        OWL + "ObjectProperty", EntityKind.OBJECT_PROPERTY,
        OWL + "DatatypeProperty", EntityKind.DATA_PROPERTY,
        OWL + "AnnotationProperty", EntityKind.ANNOTATION_PROPERTY,
        RDFS + "Datatype", EntityKind.DATATYPE,
        OWL + "NamedIndividual", EntityKind.INDIVIDUAL);

    private final SparqlDatasetService dataset;
    private final String projectId;
    private final Map<String, String> prefixes;
    private final String defaultNamespace;
    private final Map<String, Optional<KnownEntity>> byIri = new HashMap<>();
    private final Map<String, List<KnownEntity>> byLabel = new HashMap<>();

    public SparqlOntologyLookup(SparqlDatasetService dataset, String projectId) {
        this.dataset = dataset;
        this.projectId = projectId;
        this.prefixes = loadPrefixes();
        this.defaultNamespace = resolveDefaultNamespace();
    }

    @Override
    public String defaultNamespace() {
        return defaultNamespace;
    }

    @Override
    public Map<String, String> prefixes() {
        return prefixes;
    }

    @Override
    public Optional<KnownEntity> byIri(String iri) {
        if (!AxiomGenerator.isSafeIri(iri)) {
            return Optional.empty();
        }
        return byIri.computeIfAbsent(iri, this::queryIri);
    }

    @Override
    public List<KnownEntity> byLabel(String label) {
        if (label == null || label.isBlank()) {
            return List.of();
        }
        return byLabel.computeIfAbsent(label, this::queryLabel);
    }

    private static final java.util.regex.Pattern SHORT_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.\\-]+");

    @Override
    public List<KnownEntity> byShortName(String name) {
        if (name == null || !SHORT_NAME.matcher(name).matches()) {
            return List.of();
        }
        List<String> iris = shortNameIndex().getOrDefault(name, List.of());
        List<KnownEntity> result = new ArrayList<>(iris.size());
        for (String iri : iris) {
            byIri(iri).ifPresent(result::add);
        }
        return result;
    }

    private Map<String, List<String>> shortNames;

    private Map<String, List<String>> shortNameIndex() {
        if (shortNames != null) {
            return shortNames;
        }
        String query = PREFIXES + """
            SELECT DISTINCT ?s WHERE {
              VALUES ?t { owl:Class rdfs:Class owl:ObjectProperty owl:DatatypeProperty
                          owl:AnnotationProperty owl:NamedIndividual rdfs:Datatype }
              ?s rdf:type ?t .
              FILTER(isIRI(?s))
            }
            """;
        Map<String, List<String>> index = new HashMap<>();
        try (TupleQueryResult rs = dataset.execSelect(projectId, query)) {
            while (rs.hasNext()) {
                String iri = rs.next().getValue("s").stringValue();
                int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
                if (cut >= 0 && cut < iri.length() - 1) {
                    index.computeIfAbsent(iri.substring(cut + 1), k -> new ArrayList<>()).add(iri);
                }
            }
        } catch (Exception e) {
            log.warn("[SpreadsheetImport] Short-name index failed for project {}: {}", projectId, e.getMessage());
        }
        shortNames = index;
        return shortNames;
    }

    private Optional<KnownEntity> queryIri(String iri) {
        String query = PREFIXES + """
            SELECT ?type ?range WHERE {
              { <%1$s> rdf:type ?type }
              UNION
              { <%1$s> rdfs:range ?range . FILTER(isIRI(?range)) }
            }
            """.formatted(iri);
        Set<EntityKind> kinds = EnumSet.noneOf(EntityKind.class);
        String range = null;
        try (TupleQueryResult rs = dataset.execSelect(projectId, query)) {
            while (rs.hasNext()) {
                BindingSet b = rs.next();
                if (b.hasBinding("type")) {
                    addKind(kinds, b.getValue("type").stringValue());
                }
                if (b.hasBinding("range")) {
                    String r = b.getValue("range").stringValue();
                    if (range == null || r.startsWith(XSD)) {
                        range = r;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[SpreadsheetImport] Entity lookup failed for {} in project {}: {}", iri, projectId, e.getMessage());
        }
        if (kinds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new KnownEntity(iri, Set.copyOf(kinds), kinds.contains(EntityKind.DATA_PROPERTY) ? range : null));
    }

    private List<KnownEntity> queryLabel(String label) {
        String query = PREFIXES + """
            SELECT ?s ?type WHERE {
              ?s rdfs:label ?l .
              FILTER(isIRI(?s) && STR(?l) = %s)
              OPTIONAL { ?s rdf:type ?type }
            } LIMIT 200
            """.formatted(sparqlString(label));
        Map<String, Set<EntityKind>> kindsBySubject = new HashMap<>();
        try (TupleQueryResult rs = dataset.execSelect(projectId, query)) {
            while (rs.hasNext()) {
                BindingSet b = rs.next();
                String s = b.getValue("s").stringValue();
                Set<EntityKind> kinds = kindsBySubject.computeIfAbsent(s, k -> EnumSet.noneOf(EntityKind.class));
                if (b.hasBinding("type")) {
                    addKind(kinds, b.getValue("type").stringValue());
                }
            }
        } catch (Exception e) {
            log.warn("[SpreadsheetImport] Label lookup failed for '{}' in project {}: {}", label, projectId, e.getMessage());
        }
        List<KnownEntity> result = new ArrayList<>();
        kindsBySubject.forEach((iri, kinds) -> {
            if (!kinds.isEmpty()) {
                result.add(byIri(iri).orElse(new KnownEntity(iri, Set.copyOf(kinds), null)));
            }
        });
        return List.copyOf(result);
    }

    private static void addKind(Set<EntityKind> kinds, String type) {
        EntityKind kind = TYPE_KINDS.get(type);
        if (kind != null) {
            kinds.add(kind);
        } else if (!type.startsWith(OWL) && !type.startsWith(RDF) && !type.startsWith(RDFS)) {
            kinds.add(EntityKind.INDIVIDUAL);
        }
    }

    private Map<String, String> loadPrefixes() {
        Map<String, String> result = new HashMap<>();
        try {
            dataset.getPrefixes(projectId).forEach((prefix, ns) -> {
                if (prefix != null && ns != null && AxiomGenerator.isSafeIri(ns)) {
                    result.put(prefix.endsWith(":") ? prefix.substring(0, prefix.length() - 1) : prefix, ns);
                }
            });
        } catch (Exception e) {
            log.warn("[SpreadsheetImport] Could not read prefixes for project {}: {}", projectId, e.getMessage());
        }
        return Map.copyOf(result);
    }

    private String resolveDefaultNamespace() {
        String ns = prefixes.get("");
        if (ns != null && !ns.isBlank()) {
            return ns;
        }
        String query = PREFIXES + "SELECT ?o WHERE { ?o a owl:Ontology . FILTER(isIRI(?o)) } LIMIT 1";
        try (TupleQueryResult rs = dataset.execSelect(projectId, query)) {
            if (rs.hasNext()) {
                String iri = rs.next().getValue("o").stringValue();
                if (AxiomGenerator.isSafeIri(iri)) {
                    return iri.endsWith("#") || iri.endsWith("/") ? iri : iri + "#";
                }
            }
        } catch (Exception e) {
            log.warn("[SpreadsheetImport] Could not read the ontology IRI for project {}: {}", projectId, e.getMessage());
        }
        return null;
    }

    private static String sparqlString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
