package self.research.ontology.owlEditor.service.spreadsheet.rule;

import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetParserService;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetWorkbook;

public record RuleSpec(String sheetName, String startColumn, String endColumn,
                       String startRow, String endRow, String rule, String comment, Boolean active) {

    public boolean isActive() {
        return active == null || active;
    }

    public record Range(int startColumn, int endColumn, int startRow, int endRow) {
        public boolean isEmpty() {
            return endRow < startRow || endColumn < startColumn;
        }
    }

    public Range resolve(SpreadsheetWorkbook.Sheet sheet) {
        int startCol = column(startColumn, "Start column", sheet);
        int endCol = column(endColumn, "End column", sheet);
        int startR = row(startRow, "Start row", sheet);
        int endR = row(endRow, "End row", sheet);
        if (!"+".equals(trim(endColumn)) && endCol < startCol) {
            throw new IllegalArgumentException("End column " + endColumn + " is before start column " + startColumn);
        }
        if (!"+".equals(trim(endRow)) && endR < startR) {
            throw new IllegalArgumentException("End row " + endRow + " is before start row " + startRow);
        }
        return new Range(startCol, Math.min(endCol, sheet.columnCount()), startR, Math.min(endR, sheet.rowCount()));
    }

    private static int column(String value, String field, SpreadsheetWorkbook.Sheet sheet) {
        String v = trim(value);
        if (v.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if ("+".equals(v)) {
            return sheet.columnCount();
        }
        int index = SpreadsheetParserService.columnIndex(v);
        if (index < 1) {
            throw new IllegalArgumentException(field + " must be a column letter such as A or AB, or + for the last column");
        }
        return index;
    }

    private static int row(String value, String field, SpreadsheetWorkbook.Sheet sheet) {
        String v = trim(value);
        if (v.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if ("+".equals(v)) {
            return sheet.rowCount();
        }
        try {
            int n = Integer.parseInt(v);
            if (n < 1) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a row number (1 or more), or + for the last row");
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
