package self.research.ontology.owlEditor.util;

public final class SparqlSafety {

    private SparqlSafety() {
    }

    public static String safeIri(String iri) {
        if (iri == null || iri.isBlank()) {
            throw new IllegalArgumentException("IRI must not be blank");
        }
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (c == '<' || c == '>' || c == '"' || c == '{' || c == '}' ||
                    c == '|' || c == '^' || c == '`' || c == '\\' || c <= 0x20) {
                throw new IllegalArgumentException("Invalid character in IRI at position " + i + " (char=" + (int) c + ")");
            }
        }
        return iri;
    }
}
