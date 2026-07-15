package self.research.ontology.swrl.dto;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for SQWRL query results
 */
public class SqwrlQueryResponse {

    private boolean success;
    private String queryName;
    private String queryText;
    private long executionTimeMs;
    private int rowCount;
    private List<String> columnNames;
    private List<Map<String, String>> rows;
    private String errorMessage;

    public SqwrlQueryResponse() {
    }

    // Success constructor
    public SqwrlQueryResponse(String queryName, String queryText, long executionTimeMs, 
                              List<String> columnNames, List<Map<String, String>> rows) {
        this.success = true;
        this.queryName = queryName;
        this.queryText = queryText;
        this.executionTimeMs = executionTimeMs;
        this.columnNames = columnNames;
        this.rows = rows;
        this.rowCount = rows != null ? rows.size() : 0;
    }

    // Error constructor
    public SqwrlQueryResponse(String queryName, String queryText, String errorMessage) {
        this.success = false;
        this.queryName = queryName;
        this.queryText = queryText;
        this.errorMessage = errorMessage;
        this.rowCount = 0;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getQueryName() {
        return queryName;
    }

    public void setQueryName(String queryName) {
        this.queryName = queryName;
    }

    public String getQueryText() {
        return queryText;
    }

    public void setQueryText(String queryText) {
        this.queryText = queryText;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public void setColumnNames(List<String> columnNames) {
        this.columnNames = columnNames;
    }

    public List<Map<String, String>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, String>> rows) {
        this.rows = rows;
        this.rowCount = rows != null ? rows.size() : 0;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
