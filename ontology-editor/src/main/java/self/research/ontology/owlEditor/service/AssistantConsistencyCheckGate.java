package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditInput;

import java.util.List;
import java.util.Set;

public final class AssistantConsistencyCheckGate {

    private static final Set<String> AXIOM_TOKENS = Set.of(
            "rdfs:subClassOf", "owl:equivalentClass", "owl:disjointWith", "owl:disjointUnionOf",
            "rdfs:domain", "rdfs:range",
            "owl:Restriction", "owl:onProperty", "owl:someValuesFrom", "owl:allValuesFrom", "owl:hasValue",
            "owl:minCardinality", "owl:maxCardinality", "owl:cardinality", "owl:qualifiedCardinality",
            "owl:minQualifiedCardinality", "owl:maxQualifiedCardinality",
            "owl:oneOf", "owl:unionOf", "owl:intersectionOf", "owl:complementOf",
            "owl:FunctionalProperty", "owl:InverseFunctionalProperty", "owl:TransitiveProperty",
            "owl:SymmetricProperty", "owl:AsymmetricProperty", "owl:ReflexiveProperty", "owl:IrreflexiveProperty",
            "owl:propertyChainAxiom", "owl:inverseOf");

    private AssistantConsistencyCheckGate() {
    }

    public static boolean worthChecking(String targetPath, List<EditInput> edits) {
        if (!AssistantRenameService.isSupportedFormat(targetPath)) {
            return true;
        }
        if (edits == null) {
            return false;
        }
        for (EditInput edit : edits) {
            if (edit == null) {
                continue;
            }
            if (containsAxiomToken(edit.newText()) || containsAxiomToken(edit.originalText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAxiomToken(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String token : AXIOM_TOKENS) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
