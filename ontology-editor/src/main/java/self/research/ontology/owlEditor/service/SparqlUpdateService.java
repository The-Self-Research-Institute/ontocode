package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import self.research.ontology.owlEditor.config.SparqlProperties;

@Service
public class SparqlUpdateService {

    private static final Logger log = LoggerFactory.getLogger(SparqlUpdateService.class);

    private final WebClient updateClient;
    private final SparqlProperties props;
    private final ObjectMapper mapper;

    public SparqlUpdateService(WebClient.Builder webClientBuilder,
                              SparqlProperties props,
                              ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;

        WebClient.Builder builder = webClientBuilder.baseUrl(props.getUpdateEndpointUrl());
        if (props.getUsername() != null && !props.getUsername().isBlank()) {
            builder.defaultHeaders(headers ->
                headers.setBasicAuth(props.getUsername(), props.getPassword())
            );
        }
        this.updateClient = builder.build();
    }

    public Mono<Void> executeUpdate(String update) {
        log.debug("Executing SPARQL UPDATE:\n{}", update);
        return updateClient
                .post()
                .uri("")
                .header(HttpHeaders.CONTENT_TYPE, "application/sparql-update")
                .bodyValue(update)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(response -> log.debug("SPARQL update response: {}", response))
                .doOnError(error -> log.error("SPARQL update failed", error))
                .then();
    }

    public static String lit(String value) {
        if (value == null) return "\"\"";
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    public String graph(String projectId) {
        return props.getProjectGraphUri(projectId);
    }
}