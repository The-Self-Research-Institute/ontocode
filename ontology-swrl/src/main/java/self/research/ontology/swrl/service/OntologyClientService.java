package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Service
public class OntologyClientService {

    private static final Logger logger = LoggerFactory.getLogger(OntologyClientService.class);
    private final WebClient webClient;

    public OntologyClientService(@Value("${ontology.editor.service.url}") String editorServiceUrl) {
        this.webClient = WebClient.builder()
            .baseUrl(editorServiceUrl)
            .build();
    }

    @Cacheable(value = "ontologies", key = "#projectId")
    public OWLOntology fetchOntology(String projectId) throws OWLOntologyCreationException {
        logger.info("Fetching ontology for project: {}", projectId);

        try {
            byte[] ontologyBytes = webClient.get()
                .uri("/api/ontology/export/{projectId}", projectId)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();

            if (ontologyBytes == null) {
                throw new RuntimeException("Failed to fetch ontology from editor service");
            }

            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            try (InputStream is = new ByteArrayInputStream(ontologyBytes)) {
                OWLOntology ontology = manager.loadOntologyFromOntologyDocument(is);
                logger.info("Successfully loaded ontology: {}", ontology.getOntologyID());
                return ontology;
            }

        } catch (Exception e) {
            logger.error("Failed to fetch ontology for project: {}", projectId, e);
            throw new OWLOntologyCreationException("Failed to fetch ontology: " + e.getMessage());
        }
    }
}