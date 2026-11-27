package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;

final class CitationSparql {

    private static final String PREFIXES = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            PREFIX dc: <http://purl.org/dc/elements/1.1/>
            PREFIX dcterms: <http://purl.org/dc/terms/>
            PREFIX bibo: <http://purl.org/ontology/bibo/>
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            PREFIX prov: <http://www.w3.org/ns/prov#>
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

            """;

    private CitationSparql() {
    }

    static String insertData(Model citationModel) {
        StringBuilder sparql = new StringBuilder(PREFIXES).append("INSERT DATA {\n");
        for (Statement st : citationModel) {
            sparql.append("  ").append(term(st.getSubject().toString()))
                    .append(' ').append(term(st.getPredicate().toString())).append(' ');
            if (st.getObject() instanceof Literal literal) {
                sparql.append("\"\"\"").append(escape(literal.stringValue())).append("\"\"\"");
                if (literal.getLanguage().isPresent()) {
                    sparql.append('@').append(literal.getLanguage().get());
                } else if (literal.getDatatype() != null) {
                    sparql.append("^^").append(term(literal.getDatatype().toString()));
                }
            } else {
                sparql.append(term(st.getObject().toString()));
            }
            sparql.append(" .\n");
        }
        return sparql.append('}').toString();
    }

    static String citationIri(Model model) {
        for (Statement st : model) {
            String type = st.getObject().toString();
            if (st.getPredicate().toString().contains("type")
                    && (type.contains("Article") || type.contains("Book") || type.contains("Document") || type.contains("Entity"))) {
                return st.getSubject().toString();
            }
        }
        return model.isEmpty() ? "unknown" : model.iterator().next().getSubject().toString();
    }

    private static String term(String value) {
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("urn:")) {
            return "<" + value + ">";
        }
        return value;
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
