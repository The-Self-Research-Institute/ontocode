package self.research.ontology.swrl.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.swrl.service.OwnerContext;
import self.research.ontology.swrl.service.SqwrlPager;
import self.research.ontology.swrl.service.SqwrlQueryResult;
import self.research.ontology.swrl.service.SqwrlResultStore;
import self.research.ontology.swrl.service.SwrlBusyException;
import self.research.ontology.swrl.service.SwrlEngineService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/swrl")
public class SqwrlController {

    private static final Logger logger = LoggerFactory.getLogger(SqwrlController.class);

    @Autowired
    private SwrlEngineService swrlEngineService;

    @Autowired
    private SqwrlResultStore sqwrlResults;

    @Value("${ontocode.capacity.swrl-execute.sqwrl-page-size:100}")
    private int sqwrlPageSize = 100;

    @Value("${ontocode.capacity.swrl-execute.sqwrl-max-page-size:1000}")
    private int sqwrlMaxPageSize = 1000;

    @Value("${ontocode.capacity.swrl-execute.sqwrl-max-page-bytes:8388608}")
    private long sqwrlMaxPageBytes = 8L * 1024 * 1024;

    @PostMapping("/{projectId}/sqwrl/query")
    public ResponseEntity<?> executeSqwrlQuery(@PathVariable String projectId, @RequestBody Map<String, Object> request,
                                               @RequestParam(defaultValue = "false") boolean draft,
                                               @RequestParam(required = false) String userId) {
        if (draft && (userId == null || userId.isBlank())) {
            return ResponseEntity.badRequest().body(SwrlController.createErrorResponse("userId is required to query a private draft"));
        }
        try {
            String queryText = (String) request.get("queryText");
            String queryName = (String) request.get("queryName");
            Integer maxResults = request.get("maxResults") != null ? ((Number) request.get("maxResults")).intValue() : null;
            if (queryText == null || queryText.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(SwrlController.createErrorResponse("Query text is required"));
            }
            logger.info("Executing SQWRL query for project {}: {}", projectId, queryText);
            SqwrlQueryResult result = swrlEngineService.executeSqwrlQuery(projectId, queryText, queryName, maxResults,
                    draft ? userId : null);
            if (!result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "queryName", result.getQueryName() != null ? result.getQueryName() : "",
                    "queryText", result.getQueryText() != null ? result.getQueryText() : "",
                    "errorMessage", result.getErrorMessage() != null ? result.getErrorMessage() : "Unknown error"
                ));
            }
            return ResponseEntity.ok(successBody(projectId, result, request.get("pageSize")));
        } catch (SwrlBusyException e) {
            return SwrlController.busy(e);
        } catch (Exception e) {
            logger.error("SQWRL query failed for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(SwrlController.createErrorResponse("SQWRL query failed: " + e.getMessage()));
        }
    }

    private Map<String, Object> successBody(String projectId, SqwrlQueryResult result, Object requestedPageSize) {
        int pageSize = clampPageSize(requestedPageSize instanceof Number n ? n.intValue() : null);
        List<String> columns = result.getColumnNames() != null ? result.getColumnNames() : List.of();
        List<Map<String, String>> all = result.getRows() != null ? result.getRows() : List.of();
        SqwrlResultStore.Meta meta = new SqwrlResultStore.Meta(
            result.getQueryName(), result.getQueryText(), result.getExecutionTimeMs(), result.getTotalRows(),
            result.isTotalRowsExact(), result.isTruncated(), result.getRowLimit(), result.isBytesCapped());
        Optional<SqwrlResultStore.Entry> stored = sqwrlResults.put(projectId, OwnerContext.current(), columns, all, meta);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("queryName", result.getQueryName());
        body.put("queryText", result.getQueryText());
        body.put("executionTimeMs", result.getExecutionTimeMs());
        body.put("columnNames", columns);
        body.put("totalRows", result.getTotalRows());
        body.put("totalRowsExact", result.isTotalRowsExact());
        body.put("truncated", result.isTruncated());
        body.put("rowLimit", result.getRowLimit());
        body.put("bytesCapped", result.isBytesCapped());
        if (stored.isPresent()) {
            SqwrlPager.Page page = SqwrlPager.page(stored.get(), null, null, false, 0, pageSize, sqwrlMaxPageBytes);
            body.put("rows", page.rows());
            body.put("rowCount", page.rows().size());
            body.put("runId", stored.get().id());
            body.put("pageSize", pageSize);
            body.put("storedRows", all.size());
            body.put("filteredRows", page.filteredRows());
            body.put("nextOffset", page.nextOffset());
            body.put("pageBytesCapped", page.pageBytesCapped());
        } else {
            body.put("rows", all);
            body.put("rowCount", all.size());
        }
        return body;
    }

    private int clampPageSize(Integer requested) {
        int max = Math.max(1, sqwrlMaxPageSize);
        int size = requested != null && requested > 0 ? requested : sqwrlPageSize;
        return Math.min(Math.max(1, size), max);
    }

    @GetMapping("/{projectId}/sqwrl/runs/{runId}/rows")
    public ResponseEntity<?> sqwrlRows(
            @PathVariable String projectId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "asc") String dir,
            @RequestParam(defaultValue = "false") boolean export) {
        Optional<SqwrlResultStore.Entry> found = sqwrlResults.find(runId, projectId, OwnerContext.current());
        if (found.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("expired", true);
            body.put("message", "This result has expired. Run the query again.");
            return ResponseEntity.status(HttpStatus.GONE).body(body);
        }
        SqwrlResultStore.Entry entry = found.get();
        int effective = export ? Math.max(1, entry.rows().size()) : clampPageSize(limit);
        SqwrlPager.Page page = SqwrlPager.page(entry, filter, sort, "desc".equalsIgnoreCase(dir), offset, effective, sqwrlMaxPageBytes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("columnNames", entry.columns());
        body.put("rows", page.rows());
        body.put("rowCount", page.rows().size());
        body.put("offset", page.offset());
        body.put("nextOffset", page.nextOffset());
        body.put("filteredRows", page.filteredRows());
        body.put("storedRows", entry.rows().size());
        body.put("pageBytesCapped", page.pageBytesCapped());
        return ResponseEntity.ok(body);
    }
}
