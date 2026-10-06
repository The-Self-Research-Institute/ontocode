package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class FuzzyMembershipQueryService {

    private static final String FUZZY_PREFIX = "http://fuzzy.org/ontology#";

    private final SparqlDatasetService datasetService;

    @Value("${assistant.fuzzy.timeout-seconds:15}")
    private int timeoutSeconds;

    @Value("${assistant.fuzzy.max-rows:5000}")
    private int maxRows;

    @Value("${assistant.fuzzy.max-bytes:500000}")
    private long maxBytes;

    public FuzzyMembershipQueryService(SparqlDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public List<Map<String, Object>> forIndividual(String projectId, String individualIri) {
        if (!AssistantGraphIdentifierLookup.isSafeIri(individualIri)) {
            return List.of();
        }
        String sparql = "SELECT ?class ?degree WHERE { "
                + "<" + individualIri + "> <" + FUZZY_PREFIX + "hasMembership> ?membership . "
                + "?membership <" + FUZZY_PREFIX + "inClass> ?class ; "
                + "<" + FUZZY_PREFIX + "degree> ?degree . }";

        List<Map<String, Object>> memberships = new ArrayList<>();
        try {
            SparqlDatasetService.CappedSparqlResult result =
                    datasetService.execSelectCapped(projectId, sparql, timeoutSeconds, maxRows, maxBytes);
            for (Map<String, String> row : result.rows()) {
                String classIri = row.get("class");
                String degreeStr = row.get("degree");
                if (classIri == null || degreeStr == null) {
                    continue;
                }
                try {
                    double degree = Double.parseDouble(degreeStr);
                    memberships.add(Map.of("classIri", classIri, "degree", degree));
                } catch (NumberFormatException e) {
                    log.debug("[FuzzyMembership] Skipping non-numeric degree '{}' for individual {}", degreeStr, individualIri);
                }
            }
        } catch (Exception e) {
            log.warn("[FuzzyMembership] Failed to fetch fuzzy memberships for individual {} in project {}: {}",
                    individualIri, projectId, e.getMessage());
        }
        return memberships;
    }
}
