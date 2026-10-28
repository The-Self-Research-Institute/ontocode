package self.research.ontology.owlEditor.service;

import java.util.ArrayList;
import java.util.List;

public record SparqlQueryScope(String projectId, boolean draft, List<String> graphs) {

    private static final String PROJECT_PREFIX = "http://ontocode.org/project/";
    private static final String DRAFT_PREFIX = "http://ontocode.org/draft/";

    public SparqlQueryScope {
        graphs = List.copyOf(graphs);
    }

    public static SparqlQueryScope publicScope(String projectId, String baseGraph, List<String> partitionGraphs) {
        List<String> all = new ArrayList<>();
        all.add(baseGraph);
        all.addAll(partitionGraphs);
        SparqlQueryScope scope = new SparqlQueryScope(projectId, false, all);
        scope.assertBelongsToProject(projectId, null);
        return scope;
    }

    public static SparqlQueryScope draftScope(String projectId, String userId) {
        SparqlQueryScope scope = new SparqlQueryScope(projectId, true,
                List.of(SparqlGraphUris.userDraftGraph(projectId, userId)));
        scope.assertBelongsToProject(projectId, userId);
        return scope;
    }

    public void assertBelongsToProject(String urlProjectId, String userId) {
        if (urlProjectId == null || urlProjectId.isBlank() || !urlProjectId.equals(projectId) || graphs.isEmpty()) {
            throw new SparqlQueryRejectedException("The query scope does not match the project.");
        }
        String base = SparqlGraphUris.mainProjectGraph(urlProjectId);
        if (draft) {
            String expected = SparqlGraphUris.userDraftGraph(urlProjectId, userId);
            if (graphs.size() != 1 || !graphs.get(0).equals(expected)) {
                throw new SparqlQueryRejectedException("The query scope does not match the project.");
            }
            return;
        }
        if (!graphs.get(0).equals(base)) {
            throw new SparqlQueryRejectedException("The query scope does not match the project.");
        }
        for (int i = 1; i < graphs.size(); i++) {
            String g = graphs.get(i);
            boolean foreign = (g.startsWith(PROJECT_PREFIX) || g.startsWith(DRAFT_PREFIX)) && !g.startsWith(base + "/ns/");
            if (foreign) {
                throw new SparqlQueryRejectedException("The query scope does not match the project.");
            }
        }
    }
}
