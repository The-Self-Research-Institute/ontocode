package self.research.ontology.owlEditor.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class SparqlGraphUris {

    public static final String DRAFT_NS = "http://ontocode.org/ns/draft#";
    public static final String DELETED_PREDICATE = DRAFT_NS + "deleted";

    private SparqlGraphUris() {
    }

    public static String mainProjectGraph(String projectId) {
        return "http://ontocode.org/project/"
                + URLEncoder.encode(projectId, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String userDraftGraph(String projectId, String userId) {
        String encProject = URLEncoder.encode(projectId, StandardCharsets.UTF_8).replace("+", "%20");
        String encUser = URLEncoder.encode(sanitizeUserId(userId), StandardCharsets.UTF_8).replace("+", "%20");
        return "http://ontocode.org/draft/" + encProject + "/" + encUser;
    }

    public static String excludeDraftDeletedFilter(String draftGraphUri, String entityVar) {
        if (draftGraphUri == null || draftGraphUri.isBlank()) {
            return "";
        }
        return "FILTER NOT EXISTS { GRAPH <" + draftGraphUri + "> { "
                + entityVar + " <" + DELETED_PREDICATE + "> \"true\" } } ";
    }

    private static String sanitizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "anonymous";
        }
        return userId.replaceAll("[^a-zA-Z0-9._@-]", "_");
    }
}
