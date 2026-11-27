package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Service for managing citations in ontologies.
 * Handles insertion of citation RDF data into GraphDB repositories.
 */
@Service
public class CitationService {

    private static final Logger log = LoggerFactory.getLogger(CitationService.class);
    private static final Pattern DOI_PATTERN = Pattern.compile("^10\\.\\d{4,9}/.+$", Pattern.CASE_INSENSITIVE);

    @Autowired
    private SparqlDatasetService datasetService;

    @Autowired
    private CitationHistory citationHistory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private OntologyMutationService mutationService;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private DraftTrackingService draftTracking;

    private final HttpClient doiResolverClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public void insertCitation(String projectId, String citationContent, String format, Map<String, Object> metadata,
                               RequestActor actor, boolean draft) {
        String project = projectId.replaceAll("\\$.*", "");
        try {
            Model citationModel;
            try (StringReader reader = new StringReader(citationContent)) {
                citationModel = Rio.parse(reader, "", format.equalsIgnoreCase("turtle") ? RDFFormat.TURTLE : RDFFormat.RDFXML);
            }
            String title = metadata != null && metadata.containsKey("title") ? metadata.get("title").toString() : "Citation";
            String citationIri = CitationSparql.citationIri(citationModel);
            List<String> seeds = citationModel.subjects().stream()
                .filter(subject -> subject instanceof org.eclipse.rdf4j.model.IRI)
                .map(org.eclipse.rdf4j.model.Value::stringValue)
                .toList();
            CitationHistory.Entry entry = new CitationHistory.Entry("INSERT_CITATION", citationIri, title, null, citationContent,
                "Inserted citation: " + title);
            write(project, actor, draft, entry, seeds, List.of(), DraftRecords.ADD_CITATION, CitationSparql.insertData(citationModel));
            log.info("[CitationService] Inserted citation {} into project {} (draft={})", citationIri, project, draft);
        } catch (Exception e) {
            log.error("[CitationService] Error inserting citation into project: {}", project, e);
            throw new RuntimeException("Failed to insert citation: " + e.getMessage(), e);
        }
    }

    private void write(String projectId, RequestActor actor, boolean draft, CitationHistory.Entry entry, List<String> seeds,
                       List<String> incoming, String draftRecord, String sparqlUpdate) {
        if (!draft) {
            citationHistory.around(projectId, actor, entry, seeds, incoming, () -> datasetService.execUpdate(projectId, sparqlUpdate));
            return;
        }
        if (mutationService == null) {
            throw new IllegalStateException("Draft editing is not available");
        }
        String changeSetId = citationHistory.around(projectId, actor, entry, seeds, incoming, true, actor.userId(),
                () -> mutationService.applyRawUpdate(projectId, sparqlUpdate, true, actor.userId()));
        if (changeSetId != null && draftTracking != null) {
            draftTracking.recordDescribed(projectId, actor.userId(), actor.username(), draftRecord,
                    DraftRecords.citation(entry.citationIri()), List.of(changeSetId));
        }
    }

    public Map<String, Object> validateDoi(String rawDoi, String expectedTitle, String expectedPublicationTitle, String expectedYear) {
        String normalizedDoi = normalizeDoi(rawDoi);
        if (normalizedDoi == null) {
            throw new IllegalArgumentException("Invalid DOI format. Expected format: 10.XXXX/suffix");
        }

        try {
            DoiMetadata metadata = resolveDoiMetadata(normalizedDoi);
            if (!metadata.exists()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("valid", false);
                result.put("relevant", false);
                result.put("normalizedDoi", normalizedDoi);
                result.put("error", "DOI was not found.");
                return result;
            }

            List<String> mismatches = new ArrayList<>();
            if (hasText(expectedTitle) && hasText(metadata.title()) && !isMetadataMatch(expectedTitle, metadata.title())) {
                mismatches.add("title");
            }
            if (hasText(expectedPublicationTitle)
                && hasText(metadata.publicationTitle())
                && !isMetadataMatch(expectedPublicationTitle, metadata.publicationTitle())) {
                mismatches.add("journal or publication name");
            }
            if (hasText(expectedYear) && hasText(metadata.year()) && !expectedYear.trim().equals(metadata.year())) {
                mismatches.add("year");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", true);
            result.put("relevant", mismatches.isEmpty());
            result.put("normalizedDoi", metadata.normalizedDoi());
            result.put("resolvedTitle", metadata.title());
            result.put("resolvedPublicationTitle", metadata.publicationTitle());
            result.put("resolvedYear", metadata.year());
            if (mismatches.isEmpty()) {
                result.put("message", "DOI is valid.");
            } else {
                result.put("error", "DOI is real, but it does not match the provided " + String.join(", ", mismatches) + ".");
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CitationService] DOI validation failed for {}", normalizedDoi, e);
            throw new RuntimeException("Unable to validate DOI right now.", e);
        }
    }

    /**
     * Get all citations from a project's ontology
     * 
     * @param projectId - the project ID
     * @return list of citation metadata
     */
    public List<Map<String, Object>> getCitations(String projectId) {
        try {
            log.info("[CitationService] Retrieving citations for project: {}", projectId);

            // Query for bibliographic resources (using common citation ontologies)
            String sparqlQuery = """
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX dc: <http://purl.org/dc/elements/1.1/>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                PREFIX bibo: <http://purl.org/ontology/bibo/>
                PREFIX prov: <http://www.w3.org/ns/prov#>
                
                SELECT DISTINCT ?citation ?title ?creator ?date ?doi
                WHERE {
                  {
                    ?citation rdf:type bibo:Article .
                  } UNION {
                    ?citation rdf:type bibo:Book .
                  } UNION {
                    ?citation rdf:type bibo:Document .
                  } UNION {
                    ?citation rdf:type prov:Entity .
                    ?citation dc:title ?anyTitle .
                  }
                  
                  OPTIONAL { ?citation dc:title ?title }
                  OPTIONAL { ?citation dcterms:title ?title }
                  OPTIONAL { ?citation dc:creator ?creator }
                  OPTIONAL { ?citation dcterms:creator ?creator }
                  OPTIONAL { ?citation dc:date ?date }
                  OPTIONAL { ?citation dcterms:issued ?date }
                  OPTIONAL { ?citation bibo:doi ?doi }
                }
                ORDER BY ?title
                """;

            List<Map<String, Object>> citations = new ArrayList<>();
            
            // Use SparqlDatasetService to execute query
            RepositoryConnection conn = datasetService.getConnection();
            try {
                TupleQueryResult results = datasetService.executeQuery(conn, projectId, sparqlQuery);
                
                while (results.hasNext()) {
                    BindingSet binding = results.next();
                    Map<String, Object> citation = new HashMap<>();
                    
                    if (binding.hasBinding("citation")) {
                        citation.put("iri", binding.getValue("citation").stringValue());
                    }
                    
                    if (binding.hasBinding("title")) {
                        citation.put("title", binding.getValue("title").stringValue());
                    }
                    
                    if (binding.hasBinding("creator")) {
                        citation.put("creator", binding.getValue("creator").stringValue());
                    }
                    
                    if (binding.hasBinding("date")) {
                        citation.put("date", binding.getValue("date").stringValue());
                    }
                    
                    if (binding.hasBinding("doi")) {
                        citation.put("doi", binding.getValue("doi").stringValue());
                    }
                    
                    citations.add(citation);
                }
                
                results.close();
            } finally {
                conn.close();
            }

            log.info("[CitationService] Found {} citations for project: {}", citations.size(), projectId);
            return citations;

        } catch (Exception e) {
            log.error("[CitationService] Error retrieving citations for project: {}", projectId, e);
            throw new RuntimeException("Failed to retrieve citations: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a citation from the ontology
     * 
     * @param projectId - the project ID
     * @param citationIRI - the IRI of the citation to delete
     */
    public void deleteCitation(String projectId, String citationIRI) {
        deleteCitation(projectId, citationIRI, new RequestActor("system", "Citation Manager"));
    }

    public void deleteCitation(String projectId, String citationIRI, RequestActor actor) {
        deleteCitation(projectId, citationIRI, actor, false);
    }

    public void deleteCitation(String projectId, String citationIRI, RequestActor actor, boolean draft) {
        try {
            log.info("[CitationService] Deleting citation {} from project: {}", citationIRI, projectId);

            // Delete all triples where citation is the subject OR object
            String sparqlUpdate = String.format("""
                DELETE {
                  <%s> ?p ?o .
                  ?s ?p2 <%s> .
                }
                WHERE {
                  {
                    <%s> ?p ?o .
                  }
                  UNION
                  {
                    ?s ?p2 <%s> .
                  }
                }
                """, citationIRI, citationIRI, citationIRI, citationIRI);

            CitationHistory.Entry entry = new CitationHistory.Entry("DELETE_CITATION", citationIRI, "Citation", citationIRI, null,
                    "Deleted citation: " + citationIRI);
            write(projectId, actor, draft, entry, List.of(citationIRI), List.of(citationIRI), DraftRecords.DELETE_CITATION,
                    sparqlUpdate);

            log.info("[CitationService] Successfully deleted citation from project: {}", projectId);

        } catch (Exception e) {
            log.error("[CitationService] Error deleting citation from project: {}", projectId, e);
            throw new RuntimeException("Failed to delete citation: " + e.getMessage(), e);
        }
    }


    private DoiMetadata resolveDoiMetadata(String normalizedDoi) throws Exception {
        String encodedDoi = URLEncoder.encode(normalizedDoi, StandardCharsets.UTF_8);
        HttpRequest metadataRequest = HttpRequest.newBuilder(URI.create("https://doi.org/" + encodedDoi))
            .header("Accept", "application/vnd.citationstyles.csl+json")
            .header("User-Agent", "OntoCode DOI Validator/1.0")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();

        HttpResponse<String> metadataResponse = doiResolverClient.send(metadataRequest, HttpResponse.BodyHandlers.ofString());
        if (metadataResponse.statusCode() == 404) {
            return new DoiMetadata(false, normalizedDoi, null, null, null);
        }

        if (metadataResponse.statusCode() >= 200 && metadataResponse.statusCode() < 300) {
            try {
                JsonNode root = objectMapper.readTree(metadataResponse.body());
                String resolvedDoi = firstText(root.path("DOI"));
                if (!hasText(resolvedDoi)) {
                    resolvedDoi = normalizedDoi;
                }
                return new DoiMetadata(
                    true,
                    resolvedDoi,
                    firstText(root.path("title")),
                    firstText(root.path("container-title")),
                    extractYear(root)
                );
            } catch (Exception parseError) {
                log.warn("[CitationService] Could not parse DOI metadata for {}. Falling back to existence check.", normalizedDoi, parseError);
            }
        }

        HttpRequest existenceRequest = HttpRequest.newBuilder(URI.create("https://doi.org/" + encodedDoi))
            .header("Accept", "text/plain")
            .header("User-Agent", "OntoCode DOI Validator/1.0")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        HttpResponse<Void> existenceResponse = doiResolverClient.send(existenceRequest, HttpResponse.BodyHandlers.discarding());
        boolean exists = existenceResponse.statusCode() >= 200 && existenceResponse.statusCode() < 400;
        return new DoiMetadata(exists, normalizedDoi, null, null, null);
    }

    private String normalizeDoi(String rawDoi) {
        if (rawDoi == null) {
            return null;
        }
        String stripped = rawDoi.trim()
            .replaceFirst("(?i)^https?://(dx\\.)?doi\\.org/", "")
            .replaceFirst("(?i)^doi:", "")
            .trim();
        return DOI_PATTERN.matcher(stripped).matches() ? stripped : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String firstText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = firstText(item);
                if (hasText(text)) {
                    return text;
                }
            }
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            return text.isEmpty() ? null : text;
        }
        return node.asText(null);
    }

    private String extractYear(JsonNode root) {
        for (String fieldName : List.of("issued", "published-print", "published-online", "created")) {
            JsonNode node = root.path(fieldName).path("date-parts");
            if (!node.isArray() || node.isEmpty()) {
                continue;
            }
            JsonNode firstDatePart = node.get(0);
            if (firstDatePart != null && firstDatePart.isArray() && !firstDatePart.isEmpty()) {
                JsonNode yearNode = firstDatePart.get(0);
                if (yearNode != null && yearNode.canConvertToInt()) {
                    return String.valueOf(yearNode.asInt());
                }
            }
        }
        return null;
    }

    private boolean isMetadataMatch(String expected, String actual) {
        String normalizedExpected = normalizeComparisonText(expected);
        String normalizedActual = normalizeComparisonText(actual);
        if (normalizedExpected.isEmpty() || normalizedActual.isEmpty()) {
            return true;
        }
        if (normalizedExpected.equals(normalizedActual)
            || normalizedExpected.contains(normalizedActual)
            || normalizedActual.contains(normalizedExpected)) {
            return true;
        }

        Set<String> expectedTokens = new LinkedHashSet<>(Arrays.asList(normalizedExpected.split(" ")));
        expectedTokens.removeIf(token -> token.length() < 3);
        Set<String> actualTokens = new LinkedHashSet<>(Arrays.asList(normalizedActual.split(" ")));
        actualTokens.removeIf(token -> token.length() < 3);
        if (expectedTokens.isEmpty() || actualTokens.isEmpty()) {
            return false;
        }

        Set<String> smaller = expectedTokens.size() <= actualTokens.size() ? expectedTokens : actualTokens;
        Set<String> larger = smaller == expectedTokens ? actualTokens : expectedTokens;
        long matches = smaller.stream().filter(larger::contains).count();
        double coverage = (double) matches / (double) smaller.size();
        return coverage >= 0.7d;
    }

    private String normalizeComparisonText(String value) {
        return value == null
            ? ""
            : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private record DoiMetadata(boolean exists, String normalizedDoi, String title, String publicationTitle, String year) {
    }
}
