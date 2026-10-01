package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AssistantGraphIdentifierLookup {

    public static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    public static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";
    public static final String OWL_NS = "http://www.w3.org/2002/07/owl#";
    public static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";

    public static final String OWL_CLASS = OWL_NS + "Class";
    public static final String OWL_OBJECT_PROPERTY = OWL_NS + "ObjectProperty";
    public static final String OWL_DATATYPE_PROPERTY = OWL_NS + "DatatypeProperty";
    public static final String OWL_ANNOTATION_PROPERTY = OWL_NS + "AnnotationProperty";
    public static final String OWL_NAMED_INDIVIDUAL = OWL_NS + "NamedIndividual";

    public static final List<String> DECLARATION_KINDS = List.of(
            OWL_CLASS, OWL_OBJECT_PROPERTY, OWL_DATATYPE_PROPERTY, OWL_ANNOTATION_PROPERTY, OWL_NAMED_INDIVIDUAL);

    private static final Pattern SAFE_IRI = Pattern.compile("[^\\s<>\"{}|^`\\\\\\p{Cntrl}]+");
    private static final int TIMEOUT_SECONDS = 10;
    private static final long MAX_BYTES = 200_000;

    private final SparqlDatasetService datasetService;

    public AssistantGraphIdentifierLookup(SparqlDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public static boolean isBuiltIn(String iri) {
        return iri.startsWith(RDF_NS) || iri.startsWith(RDFS_NS) || iri.startsWith(OWL_NS) || iri.startsWith(XSD_NS);
    }

    public static boolean isSafeIri(String iri) {
        return iri != null && !iri.isEmpty() && iri.length() <= 4096 && SAFE_IRI.matcher(iri).matches();
    }

    public Set<String> existing(String projectId, Collection<String> iris) {
        Set<String> candidates = safe(iris);
        if (candidates.isEmpty()) {
            return Set.of();
        }
        String query = "SELECT ?x WHERE { VALUES ?x { " + values(candidates) + " } "
                + "FILTER (EXISTS { ?x ?p1 ?o1 } || EXISTS { ?s2 ?p2 ?x } || EXISTS { ?s3 ?x ?o3 }) }";
        SparqlDatasetService.CappedSparqlResult result =
                datasetService.execSelectCapped(projectId, query, TIMEOUT_SECONDS, candidates.size() + 1, MAX_BYTES);
        requireComplete(result);
        Set<String> found = new LinkedHashSet<>();
        for (Map<String, String> row : result.rows()) {
            String value = row.get("x");
            if (value != null) {
                found.add(value);
            }
        }
        return found;
    }

    public Map<String, Set<String>> declaredKinds(String projectId, Collection<String> iris) {
        Set<String> candidates = safe(iris);
        if (candidates.isEmpty()) {
            return Map.of();
        }
        StringBuilder kinds = new StringBuilder();
        for (String kind : DECLARATION_KINDS) {
            kinds.append('<').append(kind).append("> ");
        }
        String query = "SELECT ?x ?t WHERE { VALUES ?x { " + values(candidates) + " } VALUES ?t { " + kinds
                + "} ?x <" + RDF_NS + "type> ?t }";
        SparqlDatasetService.CappedSparqlResult result = datasetService.execSelectCapped(projectId, query,
                TIMEOUT_SECONDS, candidates.size() * DECLARATION_KINDS.size() + 1, MAX_BYTES);
        requireComplete(result);
        Map<String, Set<String>> found = new HashMap<>();
        for (Map<String, String> row : result.rows()) {
            String iri = row.get("x");
            String type = row.get("t");
            if (iri != null && type != null) {
                found.computeIfAbsent(iri, k -> new LinkedHashSet<>()).add(type);
            }
        }
        return found;
    }

    private Set<String> safe(Collection<String> iris) {
        Set<String> result = new LinkedHashSet<>();
        for (String iri : iris) {
            if (isSafeIri(iri)) {
                result.add(iri);
            }
        }
        return result;
    }

    private String values(Set<String> iris) {
        StringBuilder sb = new StringBuilder();
        for (String iri : iris) {
            sb.append('<').append(iri).append("> ");
        }
        return sb.toString();
    }

    private void requireComplete(SparqlDatasetService.CappedSparqlResult result) {
        if (result.capExceeded() != null) {
            throw new IllegalStateException("graph lookup exceeded its cap (" + result.capExceeded() + ")");
        }
    }
}
