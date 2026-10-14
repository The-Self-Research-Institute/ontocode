package self.research.ontology.owlEditor.service.spreadsheet;

import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.List;

public record GenerationResult(List<Axiom> axioms, List<MutationOp> ops, List<Issue> issues, int rowsProcessed) {

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
    }

    public record Axiom(String type, String text, int rule, String sheet, int row) {
    }

    public enum Severity { ERROR, WARNING }

    public record Issue(Severity severity, int rule, String sheet, Integer row, String cell, String message,
                        Integer line, Integer column) {
    }
}
