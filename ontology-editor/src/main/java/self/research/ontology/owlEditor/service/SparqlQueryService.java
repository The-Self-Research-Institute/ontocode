package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import self.research.ontology.owlEditor.config.SparqlProperties;
import self.research.ontology.owlEditor.model.SparqlQueryEntity;
import self.research.ontology.owlEditor.repository.SparqlQueryRepository;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Service
public class SparqlQueryService {
    
    private static final Logger log = LoggerFactory.getLogger(SparqlQueryService.class);
    
    @Autowired
    private SparqlQueryRepository repo;
    
    @Autowired
    private SparqlProperties props;
    
    private final WebClient client;
    private final ObjectMapper om;

    public SparqlQueryService(SparqlQueryRepository repo,
                             WebClient.Builder builder, 
                             SparqlProperties props,
                             ObjectMapper om) {
        this.repo = repo;
        this.props = props;
        this.om = om;
        
        WebClient.Builder webClientBuilder = builder.baseUrl(props.getEndpointUrl());
        if (props.getUsername() != null && !props.getUsername().isBlank()) {
            webClientBuilder.defaultHeaders(headers -> 
                headers.setBasicAuth(props.getUsername(), props.getPassword())
            );
        }
        this.client = webClientBuilder.build();
    }

    // ========== QUERY MANAGEMENT ==========

    public List<SparqlQueryEntity> list(String projectId) { 
        return repo.findByProjectId(projectId); 
    }

    public SparqlQueryEntity create(String projectId, String name, String queryText) {
        SparqlQueryEntity entity = new SparqlQueryEntity();
        entity.setProjectId(projectId);
        entity.setName(name);
        entity.setQueryText(queryText);
        return repo.save(entity);
    }

    public SparqlQueryEntity update(String id, String name, String queryText) {
        SparqlQueryEntity entity = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Query not found: " + id));
        entity.setName(name);
        entity.setQueryText(queryText);
        entity.setUpdatedAt(new Date());
        return repo.save(entity);
    }

    public void delete(String id) { 
        repo.deleteById(id); 
    }

    // ========== QUERY EXECUTION ==========

    public Mono<JsonNode> execute(String projectId, String queryText) {
        log.debug("Executing SPARQL query for project: {}", projectId);
        
        String graph = props.getProjectGraphUri(projectId);
        String upper = queryText.toUpperCase();
        
        // Enrich query with project graph if not specified
        String enrichedQuery = (upper.contains("FROM") || upper.contains("GRAPH"))
                ? queryText
                : queryText.replaceFirst("(?i)WHERE", "\nFROM <" + graph + ">\nWHERE");

        return client.post()
                .uri("")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .header(HttpHeaders.ACCEPT, "application/sparql-results+json")
                .bodyValue("query=" + URLEncoder.encode(enrichedQuery, StandardCharsets.UTF_8))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(response -> {
                    try {
                        return Mono.just(om.readTree(response));
                    } catch (Exception e) {
                        log.error("Failed to parse SPARQL response", e);
                        return Mono.error(new RuntimeException("Failed to parse response", e));
                    }
                })
                .doOnError(e -> log.error("SPARQL query execution failed", e));
    }
}