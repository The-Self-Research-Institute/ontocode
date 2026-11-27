package self.research.ontology.swrl.service;

import java.util.List;
import java.util.Map;

public class SqwrlQueryResult {
    private boolean success;
    private String queryName;
    private String queryText;
    private long executionTimeMs;
    private List<String> columnNames;
    private List<Map<String, String>> rows;
    private String errorMessage;

    private int totalRows;
    private boolean totalRowsExact = true;
    private boolean truncated;
    private int rowLimit;
    private boolean bytesCapped;

    public SqwrlQueryResult(boolean success, String queryName, String queryText,
                           long executionTimeMs, List<String> columnNames,
                           List<Map<String, String>> rows, String errorMessage,
                           int totalRows, boolean totalRowsExact, boolean truncated, int rowLimit, boolean bytesCapped) {
        this(success, queryName, queryText, executionTimeMs, columnNames, rows, errorMessage);
        this.totalRows = totalRows;
        this.totalRowsExact = totalRowsExact;
        this.truncated = truncated;
        this.rowLimit = rowLimit;
        this.bytesCapped = bytesCapped;
    }

    public SqwrlQueryResult(boolean success, String queryName, String queryText,
                           long executionTimeMs, List<String> columnNames,
                           List<Map<String, String>> rows, String errorMessage) {
        this.success = success;
        this.queryName = queryName;
        this.queryText = queryText;
        this.executionTimeMs = executionTimeMs;
        this.columnNames = columnNames;
        this.rows = rows;
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() { return success; }
    public String getQueryName() { return queryName; }
    public String getQueryText() { return queryText; }
    public long getExecutionTimeMs() { return executionTimeMs; }
    public List<String> getColumnNames() { return columnNames; }
    public List<Map<String, String>> getRows() { return rows; }
    public int getRowCount() { return rows != null ? rows.size() : 0; }
    public String getErrorMessage() { return errorMessage; }
    public int getTotalRows() { return totalRows; }
    public boolean isTotalRowsExact() { return totalRowsExact; }
    public boolean isTruncated() { return truncated; }
    public int getRowLimit() { return rowLimit; }
    public boolean isBytesCapped() { return bytesCapped; }
}
