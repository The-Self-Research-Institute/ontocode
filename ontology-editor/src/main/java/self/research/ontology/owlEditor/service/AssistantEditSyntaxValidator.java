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

    private final StorageManager storageManager;
    private final LineRangeSpliceWriter spliceWriter;

    public AssistantEditSyntaxValidator(StorageManager storageManager, LineRangeSpliceWriter spliceWriter) {
        this.storageManager = storageManager;
        this.spliceWriter = spliceWriter;
    }

    public boolean isValid(String projectId, String targetPath, List<LineRangeSpliceWriter.SpliceEdit> edits) {
        if (!isRdf4jParseable(targetPath)) {
            return true;
        }
        Path splicedFile = null;
        try {
            Path sourceFile = storageManager.ensureCodeViewFile(projectId, targetPath);
            String extension = storageManager.extensionFor(targetPath);
            splicedFile = spliceWriter.splice(sourceFile, extension, edits);
            RDFFormat format = rdfFormatFor(targetPath);
            try (InputStream is = Files.newInputStream(splicedFile)) {
                var parser = Rio.createParser(format);
                parser.setRDFHandler(new AbstractRDFHandler() {});
                parser.parse(is, "");
            }
            return true;
        } catch (Exception e) {
            log.warn("[Assistant] Syntax check failed for project {} targetPath {}: {}",
                    projectId, targetPath, e.getMessage());
            return false;
        } finally {
            if (splicedFile != null) {
                try {
                    Files.deleteIfExists(splicedFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public boolean isRdf4jParseable(String format) {
        String lower = format.toLowerCase(Locale.ROOT);
        return !(lower.equals("owlxml") || lower.equals("manchester") || lower.equals("manchestersyntax")
                || lower.equals("functional") || lower.equals("functionalsyntax"));
    }

    private RDFFormat rdfFormatFor(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "turtle", "ttl" -> RDFFormat.TURTLE;
            case "ntriples", "nt" -> RDFFormat.NTRIPLES;
            default -> RDFFormat.RDFXML;
        };
    }
}
