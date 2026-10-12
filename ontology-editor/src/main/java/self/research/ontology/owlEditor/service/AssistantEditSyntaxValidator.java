package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class AssistantEditSyntaxValidator {

    private static final int MAX_DETAIL_CHARS = 300;

    private final StorageManager storageManager;
    private final LineRangeSpliceWriter spliceWriter;

    public AssistantEditSyntaxValidator(StorageManager storageManager, LineRangeSpliceWriter spliceWriter) {
        this.storageManager = storageManager;
        this.spliceWriter = spliceWriter;
    }

    public record SyntaxResult(boolean valid, String detail) {}

    public boolean isValid(String projectId, String targetPath, List<LineRangeSpliceWriter.SpliceEdit> edits) {
        return isValid(projectId, targetPath, edits, StorageManager.ContentScope.publicScope());
    }

    public boolean isValid(String projectId, String targetPath, List<LineRangeSpliceWriter.SpliceEdit> edits,
                           StorageManager.ContentScope scope) {
        return check(projectId, targetPath, edits, scope).valid();
    }

    public SyntaxResult check(String projectId, String targetPath, List<LineRangeSpliceWriter.SpliceEdit> edits) {
        return check(projectId, targetPath, edits, StorageManager.ContentScope.publicScope());
    }

    public SyntaxResult check(String projectId, String targetPath, List<LineRangeSpliceWriter.SpliceEdit> edits,
                              StorageManager.ContentScope scope) {
        if (!isRdf4jParseable(targetPath)) {
            return new SyntaxResult(true, "Not parsed at propose time for " + targetPath
                    + "; the OWL API import on apply validates it.");
        }
        Path sourceFile = null;
        Path splicedFile = null;
        try {
            sourceFile = scope.draft() ? storageManager.resolveCodeViewFile(projectId, targetPath, scope)
                                       : storageManager.ensureCodeViewFile(projectId, targetPath);
            long started = System.nanoTime();
            if (RegionSyntaxCheck.parses(sourceFile, targetPath, edits)) {
                log.info("[PERF] Syntax check parsed only the edited region in {}ms",
                        (System.nanoTime() - started) / 1_000_000);
                return new SyntaxResult(true, null);
            }
            String extension = storageManager.extensionFor(targetPath);
            splicedFile = spliceWriter.splice(sourceFile, extension, edits);
            RDFFormat format = rdfFormatFor(targetPath);
            try (InputStream is = Files.newInputStream(splicedFile)) {
                var parser = Rio.createParser(format);
                parser.setRDFHandler(new AbstractRDFHandler() {});
                parser.parse(is, "");
            }
            log.info("[PERF] Syntax check parsed the whole file in {}ms", (System.nanoTime() - started) / 1_000_000);
            return new SyntaxResult(true, null);
        } catch (Exception e) {
            log.warn("[Assistant] Syntax check failed for project {} targetPath {}: {}",
                    projectId, targetPath, e.getMessage());
            return new SyntaxResult(false, "The document would not parse as " + targetPath + " after these edits: "
                    + truncate(String.valueOf(e.getMessage())));
        } finally {
            if (splicedFile != null) {
                try {
                    Files.deleteIfExists(splicedFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public boolean regionParses(String projectId, String targetPath, List<LineRangeSpliceWriter.SpliceEdit> edits,
                                StorageManager.ContentScope scope) {
        try {
            if (!isRdf4jParseable(targetPath)) {
                return false;
            }
            Path sourceFile = scope.draft() ? storageManager.resolveCodeViewFile(projectId, targetPath, scope)
                                       : storageManager.ensureCodeViewFile(projectId, targetPath);
            return RegionSyntaxCheck.parses(sourceFile, targetPath, edits);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isRdf4jParseable(String format) {
        String lower = format.toLowerCase(Locale.ROOT);
        return !(lower.equals("owlxml") || lower.equals("manchester") || lower.equals("manchestersyntax")
                || lower.equals("functional") || lower.equals("functionalsyntax") || lower.equals("obo"));
    }

    private RDFFormat rdfFormatFor(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "turtle", "ttl" -> RDFFormat.TURTLE;
            case "ntriples", "nt" -> RDFFormat.NTRIPLES;
            case "jsonld" -> RDFFormat.JSONLD;
            default -> RDFFormat.RDFXML;
        };
    }

    private static String truncate(String message) {
        return message.length() <= MAX_DETAIL_CHARS ? message : message.substring(0, MAX_DETAIL_CHARS) + "...";
    }
}
