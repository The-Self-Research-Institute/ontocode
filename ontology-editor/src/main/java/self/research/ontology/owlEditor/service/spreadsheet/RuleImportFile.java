package self.research.ontology.owlEditor.service.spreadsheet;

import java.util.List;
import java.util.Map;

public record RuleImportFile(String fileName, String hash, List<Row> rows, Map<String, String> prefixes) {

    public static final String KIND_INVALID = "invalid";
    public static final String KIND_FORMULA = "formula_cell";
    public static final String KIND_BAD_ENABLED = "bad_enabled";

    public record Row(String sheet, int row, String name, String text, String comment, String category,
                      Boolean enabled, String problemKind, String problem) {
    }

    public static class RejectedException extends RuntimeException {
        private final int status;

        public RejectedException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
