package self.research.ontology.owlEditor.util;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;

import java.util.List;

public final class StandardVocabulary {

    private static final List<String> NAMESPACES = List.of(
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://www.w3.org/2000/01/rdf-schema#",
            "http://www.w3.org/2002/07/owl#",
            "http://www.w3.org/2001/XMLSchema#");

    private StandardVocabulary() {
    }

    public static boolean contains(OWLEntity entity) {
        return entity.isBuiltIn() || contains(entity.getIRI());
    }

    public static boolean contains(IRI iri) {
        return contains(iri.toString());
    }

    public static boolean contains(String iri) {
        for (String namespace : NAMESPACES) {
            if (iri.startsWith(namespace)) {
                return true;
            }
        }
        return false;
    }
}
