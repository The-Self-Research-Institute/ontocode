package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.util.SubjectRangeIndex;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class PrefixOnlyEdits {

    private static final Pattern PREFIX_LINE = Pattern.compile(
            "^\\s*(@prefix\\s+[A-Za-z]?[\\w.-]*:\\s*<[^>]*>\\s*\\.|(?i:prefix)\\s+[A-Za-z]?[\\w.-]*:\\s*<[^>]*>)\\s*$");

    private PrefixOnlyEdits() {
    }

    static Set<Long> find(String format, List<TriplePatchPlanner.Edit> edits, SubjectRangeIndex oldIndex,
                          SubjectRangeIndex newIndex, Path newFile) throws IOException {
        Set<Long> headerOnly = new HashSet<>();
        List<TriplePatchPlanner.Edit> touching = new ArrayList<>();
        for (TriplePatchPlanner.Edit edit : edits) {
            long oldLast = edit.startLine() + Math.max(edit.lineCount(), 1) - 1;
            long newLast = edit.startLine() + Math.max(edit.newLineCount(), 1) - 1;
            boolean touchesOld = edit.lineCount() > 0 && oldIndex.touchesHeader(edit.startLine(), oldLast);
            boolean touchesNew = edit.newLineCount() > 0 && newIndex.touchesHeader(edit.startLine(), newLast);
            if (touchesOld || touchesNew) {
                touching.add(edit);
            }
        }
        if (touching.isEmpty()) {
            return headerOnly;
        }
        if (!CodeViewSubjectIndex.isTurtleFamily(format) || !keepsExistingPrefixes(oldIndex, newIndex)) {
            return null;
        }
        for (TriplePatchPlanner.Edit edit : touching) {
            if (edit.lineCount() > 0 || !onlyPrefixLines(newFile, edit.startLine(), edit.newLineCount())) {
                return null;
            }
            headerOnly.add(edit.startLine());
        }
        return headerOnly;
    }

    private static boolean keepsExistingPrefixes(SubjectRangeIndex oldIndex, SubjectRangeIndex newIndex) {
        for (Map.Entry<String, String> prefix : oldIndex.prefixes().entrySet()) {
            if (!prefix.getValue().equals(newIndex.prefixes().get(prefix.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean onlyPrefixLines(Path file, long start, int count) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNo = 0;
            while ((line = reader.readLine()) != null && lineNo < start + count) {
                if (lineNo >= start && !line.isBlank() && !PREFIX_LINE.matcher(line).matches()) {
                    return false;
                }
                lineNo++;
            }
        }
        return true;
    }
}
