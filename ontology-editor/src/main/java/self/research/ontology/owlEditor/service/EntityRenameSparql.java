package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.util.SparqlSafety;

public final class EntityRenameSparql {

    private EntityRenameSparql() {
    }

    public static String build(String fromIri, String toIri) {
        String from = "<" + SparqlSafety.safeIri(fromIri) + ">";
        String to = "<" + SparqlSafety.safeIri(toIri) + ">";
        return "DELETE { ?s ?p " + from + " } INSERT { ?s ?p " + to + " } WHERE { ?s ?p " + from + " } ;\n"
                + "DELETE { " + from + " ?p ?o } INSERT { " + to + " ?p ?o } WHERE { " + from + " ?p ?o } ;\n"
                + "DELETE { ?s " + from + " ?o } INSERT { ?s " + to + " ?o } WHERE { ?s " + from + " ?o }";
    }
}
