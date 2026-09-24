package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AssistantEditReferenceCoverageValidator {

    private static final Pattern IDENTIFIER_TOKEN =
            Pattern.compile("<[^<>\\s]+>|(?:[A-Za-z_][\\w-]*)?:[A-Za-z_][\\w.-]*");

    private final StorageManager storageManager;

    public AssistantEditReferenceCoverageValidator(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public record CoverageEdit(long startLine, int lineCount, String originalText, String newText) {}

    public record CoverageResult(boolean covered, String detail) {}

    public CoverageResult check(String projectId, String targetPath, List<CoverageEdit> edits) {
        Set<String> removedTokens = new LinkedHashSet<>();
        Set<String> retainedTokens = new HashSet<>();
        for (CoverageEdit edit : edits) {
            Set<String> oldSubjectTokens = extractSubjectPositionTokens(edit.originalText());
            Set<String> newTokens = extractTokens(edit.newText());
            retainedTokens.addAll(newTokens);
            for (String token : oldSubjectTokens) {
                if (!newTokens.contains(token)) {
                    removedTokens.add(token);
                }
            }
        }
        removedTokens.removeAll(retainedTokens);
        if (removedTokens.isEmpty()) {
            return new CoverageResult(true, null);
        }

        List<long[]> editedRanges = edits.stream()
                .map(e -> new long[]{e.startLine(), e.startLine() + e.lineCount()})
                .toList();

        List<String> missed = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>(removedTokens);
        TurtleLineScanner scanner = AssistantRenameService.isTurtleFamily(targetPath) ? new TurtleLineScanner() : null;
        try {
            Path sourceFile = storageManager.ensureCodeViewFile(projectId, targetPath);
            try (BufferedReader reader = Files.newBufferedReader(sourceFile)) {
                String line;
                long lineNo = 0;
                while (!unresolved.isEmpty() && (line = reader.readLine()) != null) {
                    Set<String> lineTokens = scanner != null ? termTexts(scanner.scan(line)) : null;
                    if (!withinAnyRange(lineNo, editedRanges)) {
                        if (lineTokens == null) {
                            lineTokens = extractTokens(line);
                        }
                        Iterator<String> it = unresolved.iterator();
                        while (it.hasNext()) {
                            String token = it.next();
                            if (lineTokens.contains(token)) {
                                missed.add(token + " still appears at line " + lineNo);
                                it.remove();
                            }
                        }
                    }
                    lineNo++;
                }
            }
        } catch (Exception e) {
            log.warn("[Assistant] complete_reference_coverage scan skipped for project {} targetPath {}: {}",
                    projectId, targetPath, e.getMessage());
            return new CoverageResult(true, null);
        }

        if (missed.isEmpty()) {
            return new CoverageResult(true, null);
        }
        return new CoverageResult(false,
                "This proposal removes an identifier from the edited ranges, but it still appears elsewhere "
                        + "in the document, which this group would leave untouched: " + String.join("; ", missed)
                        + ". Include those occurrences in this group, or confirm they're intentionally kept.");
    }

    private Set<String> extractSubjectPositionTokens(String text) {
        Set<String> subjects = new LinkedHashSet<>();
        if (text == null) {
            return subjects;
        }
        for (String line : text.split("\n", -1)) {
            Matcher matcher = IDENTIFIER_TOKEN.matcher(line.stripLeading());
            if (matcher.lookingAt()) {
                subjects.add(matcher.group());
            }
        }
        return subjects;
    }

    private Set<String> extractTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null) {
            return tokens;
        }
        Matcher matcher = IDENTIFIER_TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private Set<String> termTexts(List<TurtleLineScanner.Token> tokens) {
        Set<String> texts = new HashSet<>();
        for (TurtleLineScanner.Token token : tokens) {
            if (token.isTerm()) {
                texts.add(token.text());
            }
        }
        return texts;
    }

    private boolean withinAnyRange(long lineNo, List<long[]> ranges) {
        for (long[] range : ranges) {
            if (lineNo >= range[0] && lineNo < range[1]) {
                return true;
            }
        }
        return false;
    }
}
