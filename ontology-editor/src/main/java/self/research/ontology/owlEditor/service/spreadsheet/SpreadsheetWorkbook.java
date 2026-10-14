package self.research.ontology.owlEditor.service.spreadsheet;

import java.util.List;

public record SpreadsheetWorkbook(String fileName, String format, List<Sheet> sheets) {

    public record Sheet(String name, int rowCount, int columnCount, List<List<String>> rows) {

        public String cell(int row, int column) {
            if (row < 1 || row > rows.size() || column < 1 || column > columnCount) {
                return "";
            }
            return rows.get(row - 1).get(column - 1);
        }
    }

    public Sheet sheet(String name) {
        return sheets.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }
}
