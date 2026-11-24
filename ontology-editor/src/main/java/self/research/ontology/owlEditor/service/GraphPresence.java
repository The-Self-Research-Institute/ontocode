package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.util.List;

final class GraphPresence {

    private GraphPresence() {
    }

    static boolean any(RepositoryConnection conn, List<String> graphUris) {
        for (String graphUri : graphUris) {
            if (conn.prepareBooleanQuery("ASK FROM <" + graphUri + "> { ?s ?p ?o }").evaluate()) {
                return true;
            }
        }
        return false;
    }
}
