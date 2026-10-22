package self.research.ontology.owlEditor.service.spreadsheet.rule;

import java.util.List;

public final class RuleAst {

    private RuleAst() {
    }

    public record TransformationRule(EntityKind kind, Value subject, List<Clause> clauses) {
    }

    public record Clause(ClauseKind kind, List<Item> items, int line, int column) {
    }

    public record Item(Value property, Value value) {
    }

    public sealed interface Value permits Name, CellReference, Literal {
        int line();

        int column();
    }

    public record Name(String text, NameForm form, int line, int column) implements Value {
    }

    public enum NameForm { PLAIN, PREFIXED, FULL_IRI, QUOTED }

    public record CellReference(String sheet, Integer sheetColumn, Integer row, List<Directive> directives,
                                String source, int line, int column) implements Value {

        public boolean currentColumn() {
            return sheetColumn == null;
        }

        public boolean currentRow() {
            return row == null;
        }
    }

    public record Literal(String text, String datatype, String language, int line, int column) implements Value {
    }

    public record Directive(String name, List<String> arguments, int line, int column) {
        public String argument() {
            return arguments.isEmpty() ? null : arguments.get(0);
        }
    }
}
