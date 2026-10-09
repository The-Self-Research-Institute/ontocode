package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class CodeViewRangeMatcher {

    public record ExpectedRange(long startLine, int lineCount, String originalText) {}

    public record MatchResult(boolean matches, String detail) {
        static final MatchResult OK = new MatchResult(true, null);

        static MatchResult mismatch(String detail) {
            return new MatchResult(false, detail);
        }
    }

    private final StorageManager storageManager;

    public CodeViewRangeMatcher(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public boolean allMatch(String projectId, String format, List<ExpectedRange> ranges) {
        return matchWithDetail(projectId, format, ranges).matches();
    }

    public boolean allMatch(String projectId, String format, List<ExpectedRange> ranges,
                            StorageManager.ContentScope scope) {
        return matchWithDetail(projectId, format, ranges, scope).matches();
    }

    public MatchResult matchWithDetail(String projectId, String format, List<ExpectedRange> ranges) {
        List<ExpectedRange> sorted = sortAndFilter(ranges);
        if (sorted.isEmpty()) {
            return MatchResult.OK;
        }
        MatchResult overlap = checkOverlap(sorted);
        if (overlap != null) {
            return overlap;
        }
        try {
            Path file = storageManager.ensureCodeViewFile(projectId, format);
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return matchSequentially(reader, sorted);
            }
        } catch (Exception e) {
            log.warn("[Assistant] Live-content check failed for {} {}: {}", projectId, format, e.getMessage());
            return MatchResult.mismatch("Could not read the document to check it (" + e.getMessage() + ").");
        }
    }

    public MatchResult matchWithDetail(String projectId, String format, List<ExpectedRange> ranges,
                                       StorageManager.ContentScope scope) {
        if (!scope.draft()) {
            return matchWithDetail(projectId, format, ranges);
        }
        List<ExpectedRange> sorted = sortAndFilter(ranges);
        if (sorted.isEmpty()) {
            return MatchResult.OK;
        }
        MatchResult overlap = checkOverlap(sorted);
        if (overlap != null) {
            return overlap;
        }
        try {
            Path file = storageManager.resolveCodeViewFile(projectId, format, scope);
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return matchSequentially(reader, sorted);
            }
        } catch (Exception e) {
            log.warn("[Assistant] Live-content check failed for {} {}: {}", projectId, format, e.getMessage());
            return MatchResult.mismatch("Could not read the document to check it (" + e.getMessage() + ").");
        }
    }

    private static List<ExpectedRange> sortAndFilter(List<ExpectedRange> ranges) {
        return ranges.stream()
                .filter(r -> r.lineCount() > 0)
                .sorted(Comparator.comparingLong(ExpectedRange::startLine))
                .toList();
    }

    private static MatchResult checkOverlap(List<ExpectedRange> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            ExpectedRange previous = sorted.get(i - 1);
            if (previous.startLine() + previous.lineCount() > sorted.get(i).startLine()) {
                return MatchResult.mismatch("Two of this edit's expected line ranges overlap in the document.");
            }
        }
        return null;
    }

    private static MatchResult matchSequentially(BufferedReader reader, List<ExpectedRange> sorted) throws IOException {
        long lineNo = 0;
        String line = reader.readLine();
        for (ExpectedRange range : sorted) {
            while (line != null && lineNo < range.startLine()) {
                line = reader.readLine();
                lineNo++;
            }
            StringBuilder live = new StringBuilder();
            for (int k = 0; k < range.lineCount(); k++) {
                if (line == null) {
                    return MatchResult.mismatch("The document is shorter than expected at line "
                            + (range.startLine() + 1) + " — it may have changed since this edit was proposed.");
                }
                if (k > 0) {
                    live.append('\n');
                }
                live.append(line);
                line = reader.readLine();
                lineNo++;
            }
            if (!live.toString().equals(range.originalText())) {
                return MatchResult.mismatch("The document changed at line " + (range.startLine() + 1)
                        + " since this edit was proposed.");
            }
        }
        return MatchResult.OK;
    }
}
