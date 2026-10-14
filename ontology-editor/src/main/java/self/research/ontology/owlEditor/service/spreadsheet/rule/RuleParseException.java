package self.research.ontology.owlEditor.service.spreadsheet.rule;

public class RuleParseException extends RuntimeException {

    private final int line;
    private final int column;

    public RuleParseException(String message, int line, int column) {
        super("Line " + line + ", column " + column + ": " + message);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
