package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditInput;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class AssistantConsistencyCheckGate {

    private static final Pattern TURTLE_PREFIX_LINE = Pattern.compile(
            "^\\s*(@prefix\\s+[A-Za-z]?[\\w.-]*:\\s*<[^>]*>\\s*\\.|(?i:prefix)\\s+[A-Za-z]?[\\w.-]*:\\s*<[^>]*>)\\s*$");
    private static final Pattern XML_NAMESPACE_LINE = Pattern.compile(
            "^\\s*xmlns(:[A-Za-z][\\w.-]*)?=\"[^\"]*\"\\s*$");

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
        if (edits.stream().allMatch(AssistantConsistencyCheckGate::isNamespaceDeclarationOnly)) {
            return false;
        }
        return !AssistantRenameService.isSupportedFormat(targetPath);
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

    private static boolean isNamespaceDeclarationOnly(EditInput edit) {
        if (edit == null) {
            return true;
        }
        return isNamespaceDeclarationOnlyText(edit.newText()) && isNamespaceDeclarationOnlyText(edit.originalText());
    }

    private static boolean isNamespaceDeclarationOnlyText(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!TURTLE_PREFIX_LINE.matcher(line).matches() && !XML_NAMESPACE_LINE.matcher(trimmed).matches()) {
                return false;
            }
        }
        return true;
    }
}
