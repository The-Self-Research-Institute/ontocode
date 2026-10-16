package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class HistoryCreationParent {

    private static final String RDFS_SUBCLASSOF = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    private static final String RDFS_SUBPROPERTYOF = "http://www.w3.org/2000/01/rdf-schema#subPropertyOf";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String OWL_THING_IRI = "http://www.w3.org/2002/07/owl#Thing";

    private HistoryCreationParent() {
    }

    static void addTo(MutationOp primary, List<Map<String, String>> subChanges) {
        if (primary == null || primary.type() == null || !primary.type().startsWith("create")) {
            return;
        }
        String predicate;
        String parent;
        switch (primary.type()) {
            case "createClass" -> {
                predicate = RDFS_SUBCLASSOF;
                parent = primary.parent();
            }
            case "createObjectProperty", "createDataProperty", "createAnnotationProperty" -> {
                predicate = RDFS_SUBPROPERTYOF;
                parent = primary.parent();
            }
            case "createIndividual" -> {
                predicate = RDF_TYPE;
                parent = primary.classIri();
            }
            default -> {
                return;
            }
        }
        if (parent == null || parent.isBlank() || OWL_THING_IRI.equals(parent)) {
            return;
        }
        for (Map<String, String> existing : subChanges) {
            if (predicate.equals(existing.get("predicate")) && parent.equals(existing.get("newValue"))) {
                return;
            }
        }
        Map<String, String> sc = new HashMap<>();
        sc.put("predicate", predicate);
        sc.put("newValue", parent);
        sc.put("addition", "true");
        subChanges.add(0, sc);
    }
}
