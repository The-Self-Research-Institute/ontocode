package self.research.ontology.owlEditor.dto;

import java.util.List;

public record ProposeEditRequest(List<EditGroupInput> groups) {

    public record EditRange(long startLine, int lineCount) {}

    public record EditInput(String targetPath, EditRange range, String originalText, String newText) {}

    public record InferredAxiomInput(String axiomType, String subjectIri, String predicateIri, String objectIri,
                                      String objectLiteral, String literalDatatypeIri, String literalLangTag) {}

    public record FuzzyMembershipInput(String entityIri, String classIri, Double degree) {}

    public record EditOperation(String type, String targetPath, String targetIdentifier, String replacementIdentifier,
                                 List<InferredAxiomInput> axioms, List<FuzzyMembershipInput> memberships) {
        public EditOperation(String type, String targetPath, String targetIdentifier, String replacementIdentifier,
                              List<InferredAxiomInput> axioms) {
            this(type, targetPath, targetIdentifier, replacementIdentifier, axioms, null);
        }
    }

    public record EditGroupInput(String clientGroupId, List<EditInput> edits, EditOperation operation) {
        public EditGroupInput(String clientGroupId, List<EditInput> edits) {
            this(clientGroupId, edits, null);
        }
    }
}
