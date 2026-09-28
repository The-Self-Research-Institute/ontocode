package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLParser;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.io.UnparsableOntologyException;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class CodeViewSaveValidator {

    private static final Pattern IRI_ATTRIBUTE_PATTERN =
            Pattern.compile("(?:rdf:about|rdf:resource|rdf:ID)=\"([^\"]*)\"");

    private static final Pattern RESOURCE_WITH_TEXT_PATTERN = Pattern.compile(
            "<([\\w:]+)(?=[^>]*\\brdf:resource=\"[^\"]*\")(?:\\s+[\\w:]+=\"[^\"]*\")*\\s*>([^<]*\\S[^<]*)</\\1>");

    private final ReasonerService reasonerService;

    @Autowired
    public CodeViewSaveValidator(@Autowired(required = false) @Nullable ReasonerService reasonerService) {
        this.reasonerService = reasonerService;
    }

    public Optional<Map<String, Object>> validate(String projectId, String format, String content) {
        OWLOntology parsed;
        try {
            parsed = parse(content);
        } catch (Exception parseEx) {
            return Optional.of(syntaxRejection(projectId, format, parseEx));
        }
        Optional<Map<String, Object>> rejection = rejectEmptyParse(projectId, format, content, parsed);
        if (rejection.isEmpty()) {
            rejection = rejectIriWithSpace(projectId, content);
        }
        if (rejection.isEmpty()) {
            rejection = rejectInconsistent(projectId, parsed);
        }
        return rejection;
    }

    private static OWLOntology parse(String content) throws Exception {
        try (InputStream input = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(input);
        }
    }

    private Map<String, Object> syntaxRejection(String projectId, String format, Exception parseEx) {
        OWLParserException located = locateParserError(format, parseEx);
        String detail = parseEx.getMessage() != null ? parseEx.getMessage() : parseEx.getClass().getSimpleName();
        String lineInfo = "";
        if (located != null) {
            lineInfo = " at line " + located.getLineNumber() + ", column " + located.getColumnNumber() + ":";
            detail = located.getMessage() != null ? located.getMessage() : detail;
        }
        log.warn("[CODE-VIEW-SAVE] Rejecting save for project {}: content failed validation:{} {}",
                projectId, lineInfo, detail);
        return rejection("SYNTAX_ERROR", "Invalid " + format + " content" + lineInfo + " " + detail);
    }

    private static OWLParserException locateParserError(String format, Exception parseEx) {
        if (parseEx instanceof OWLParserException direct) {
            return direct;
        }
        if (!(parseEx instanceof UnparsableOntologyException upe) || upe.getExceptions().isEmpty()) {
            return null;
        }
        String formatHint = switch (format.toLowerCase(Locale.ROOT)) {
            case "turtle", "ttl" -> "turtle";
            case "ntriples", "nt" -> "ntriples";
            default -> "rdfxml";
        };
        for (Map.Entry<OWLParser, OWLParserException> entry : upe.getExceptions().entrySet()) {
            if (entry.getKey().getClass().getSimpleName().toLowerCase(Locale.ROOT).contains(formatHint)) {
                return entry.getValue();
            }
        }
        return upe.getExceptions().values().iterator().next();
    }

    private Optional<Map<String, Object>> rejectEmptyParse(String projectId, String format, String content,
                                                           OWLOntology parsed) {
        if (parsed.getAxiomCount() != 0 || content.length() <= 2000) {
            return Optional.empty();
        }
        log.warn("[CODE-VIEW-SAVE] Rejecting save for project {}: parsed to 0 axioms from {} bytes of input",
                projectId, content.length());
        Matcher badElement = RESOURCE_WITH_TEXT_PATTERN.matcher(content);
        String lineInfo = badElement.find() ? " at line " + lineNumberAt(content, badElement.start()) + ":" : "";
        return Optional.of(rejection("SYNTAX_ERROR", "This content could not be understood as valid " + format
                + lineInfo + " — check for malformed elements (e.g. a property element combining rdf:resource with text content)."));
    }

    private Optional<Map<String, Object>> rejectIriWithSpace(String projectId, String content) {
        Matcher iriAttr = IRI_ATTRIBUTE_PATTERN.matcher(content);
        while (iriAttr.find()) {
            String iriValue = iriAttr.group(1);
            if (iriValue.indexOf(' ') >= 0) {
                int lineNumber = lineNumberAt(content, iriAttr.start());
                log.warn("[CODE-VIEW-SAVE] Rejecting save for project {}: IRI contains a space at line {}: {}",
                        projectId, lineNumber, iriValue);
                return Optional.of(rejection("SYNTAX_ERROR",
                        "Invalid IRI at line " + lineNumber + ": \"" + iriValue + "\" — IRIs can't contain spaces."));
            }
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> rejectInconsistent(String projectId, OWLOntology parsed) {
        if (reasonerService == null) {
            return Optional.empty();
        }
        try {
            ReasonerService.SaveConsistencyResult result = reasonerService.checkConsistencyForSave(parsed, ReasonerType.HERMIT);
            if (result.consistent) {
                return Optional.empty();
            }
            log.warn("[CODE-VIEW-SAVE] Rejecting save for project {}: {}", projectId, result.violationMessage);
            Map<String, Object> body = rejection("INCONSISTENT_ONTOLOGY", result.violationMessage);
            if (result.entity != null) {
                body.put("entity", result.entity);
            }
            return Optional.of(body);
        } catch (Exception reasonerEx) {
            log.warn("[CODE-VIEW-SAVE] Consistency check errored for project {} (continuing with save): {}",
                    projectId, reasonerEx.getMessage());
            return Optional.empty();
        }
    }

    private static int lineNumberAt(String content, int offset) {
        int lineNumber = 1;
        for (int i = 0; i < offset; i++) {
            if (content.charAt(i) == '\n') {
                lineNumber++;
            }
        }
        return lineNumber;
    }

    private static Map<String, Object> rejection(String errorType, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("errorType", errorType);
        body.put("error", message);
        return body;
    }
}
