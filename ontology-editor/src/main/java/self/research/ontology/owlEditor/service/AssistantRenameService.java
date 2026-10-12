package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.common.net.ParsedIRI;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditOperation;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class AssistantRenameService {

    public static final String RENAME_IDENTIFIER = "rename_identifier";
    public static final String SUPPORTED_FORMATS_TEXT = "turtle, ntriples and rdfxml";

    private static final Set<String> TURTLE_FORMATS = Set.of("turtle", "ttl");
    private static final Set<String> NTRIPLES_FORMATS = Set.of("ntriples", "nt");
    private static final Set<String> RDFXML_FORMATS = Set.of("rdfxml", "xml", "owl");

    private final StorageManager storageManager;
    private final AssistantGraphIdentifierLookup graphLookup;

    public AssistantRenameService(StorageManager storageManager, AssistantGraphIdentifierLookup graphLookup) {
        this.storageManager = storageManager;
        this.graphLookup = graphLookup;
    }

    public record DerivedEdit(long line, String originalText, String newText) {}

    public record RenameDerivation(boolean ok, String detail, String targetIri, String replacementIri,
                                   List<DerivedEdit> edits, int occurrences) {
        static RenameDerivation refused(String detail) {
            return new RenameDerivation(false, detail, null, null, List.of(), 0);
        }
    }

    public static boolean isSupportedFormat(String targetPath) {
        String lower = targetPath == null ? "" : targetPath.toLowerCase(Locale.ROOT);
        return TURTLE_FORMATS.contains(lower) || NTRIPLES_FORMATS.contains(lower) || RDFXML_FORMATS.contains(lower);
    }

    public static boolean isRdfXml(String targetPath) {
        return targetPath != null && RDFXML_FORMATS.contains(targetPath.toLowerCase(Locale.ROOT));
    }

    public static boolean isTurtleFamily(String targetPath) {
        String lower = targetPath == null ? "" : targetPath.toLowerCase(Locale.ROOT);
        return TURTLE_FORMATS.contains(lower) || NTRIPLES_FORMATS.contains(lower);
    }

    public RenameDerivation derive(String projectId, EditOperation operation, int maxLines) {
        return derive(projectId, operation, maxLines, StorageManager.ContentScope.publicScope());
    }

    public RenameDerivation derive(String projectId, EditOperation operation, int maxLines,
                                   StorageManager.ContentScope scope) {
        String operationProblem = operationProblem(operation);
        if (operationProblem != null) {
            return RenameDerivation.refused(operationProblem);
        }
        String targetPath = operation.targetPath();
        try {
            Path file = storageManager.resolveCodeViewFile(projectId, targetPath, scope);
            boolean rdfXml = isRdfXml(targetPath);
            Declarations declarations = rdfXml ? readRdfXmlDeclarations(file) : readTurtleDeclarations(file);
            if (declarations.problem != null) {
                return RenameDerivation.refused(declarations.problem);
            }
            IdentifierResolution target = resolveIdentifier(operation.targetIdentifier(), declarations, "targetIdentifier");
            if (target.error != null) {
                return RenameDerivation.refused(target.error);
            }
            IdentifierResolution replacement =
                    resolveIdentifier(operation.replacementIdentifier(), declarations, "replacementIdentifier");
            if (replacement.error != null) {
                return RenameDerivation.refused(replacement.error);
            }
            if (target.iri.equals(replacement.iri)) {
                return RenameDerivation.refused("targetIdentifier and replacementIdentifier are the same IRI <"
                        + target.iri + ">.");
            }
            String graphProblem = replacementInGraphProblem(projectId, replacement.iri);
            if (graphProblem != null) {
                return RenameDerivation.refused(graphProblem);
            }
            RenameScan scan = rdfXml
                    ? RdfXmlRenameScanner.scan(file, target.iri, replacement.iri, maxLines)
                    : scanTurtle(file, target.iri, replacement.iri, maxLines, NTRIPLES_FORMATS.contains(
                            targetPath.toLowerCase(Locale.ROOT)));
            if (scan.problem != null) {
                return RenameDerivation.refused(scan.problem);
            }
            if (scan.occurrences == 0) {
                log.warn("[Assistant] Rename found zero occurrences for project {} targetPath {}: resolved target=<{}> "
                                + "replacement=<{}> (as given: targetIdentifier='{}' replacementIdentifier='{}')",
                        projectId, targetPath, target.iri, replacement.iri, operation.targetIdentifier(),
                        operation.replacementIdentifier());
                return RenameDerivation.refused("<" + target.iri + "> does not occur in the " + targetPath
                        + " document, so there is nothing to rename." + localNameHint(file, target.iri));
            }
            return derived(scan, target.iri, replacement.iri);
        } catch (Exception e) {
            log.warn("[Assistant] Rename derivation failed for project {} targetPath {}: {}",
                    projectId, targetPath, e.getMessage());
            return RenameDerivation.refused("Could not read the " + targetPath + " document to derive the rename ("
                    + e.getMessage() + "), so no rename was generated.");
        }
    }

    private static String localNameHint(Path file, String iri) {
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        String localName = cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : null;
        if (localName == null || localName.isBlank()) {
            return "";
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.contains(localName)) {
                return " The name '" + localName + "' does appear in the document, but under a different IRI than <"
                        + iri + "> — check the prefix/namespace it's actually declared under.";
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static RenameDerivation derived(RenameScan scan, String targetIri, String replacementIri) {
        return new RenameDerivation(true, "Found " + scan.occurrences + " occurrence"
                + (scan.occurrences == 1 ? "" : "s") + " of <" + targetIri + "> on " + scan.edits.size()
                + " line" + (scan.edits.size() == 1 ? "" : "s") + "; each is renamed to <" + replacementIri + ">.",
                targetIri, replacementIri, scan.edits, scan.occurrences);
    }

    private static String operationProblem(EditOperation operation) {
        if (operation == null || !RENAME_IDENTIFIER.equals(operation.type())) {
            return "Unsupported operation type '" + (operation == null ? null : operation.type())
                    + "'. The only supported operation is " + RENAME_IDENTIFIER + ".";
        }
        String targetPath = operation.targetPath();
        if (targetPath == null || targetPath.isBlank()) {
            return "The rename operation has no targetPath.";
        }
        if (!isSupportedFormat(targetPath)) {
            return "Typed rename is only supported for " + SUPPORTED_FORMATS_TEXT
                    + " documents; occurrences in " + targetPath + " can't be matched exactly, so no rename was "
                    + "generated. Switch the Code View to one of the supported formats, or propose explicit edits.";
        }
        return null;
    }

    private String replacementInGraphProblem(String projectId, String replacementIri) {
        Set<String> inGraph;
        try {
            inGraph = graphLookup.existing(projectId, List.of(replacementIri));
        } catch (Exception e) {
            return "Could not confirm that <" + replacementIri
                    + "> is unused in the graph (" + e.getMessage() + "), so no rename was generated.";
        }
        if (!inGraph.isEmpty()) {
            return "The replacement <" + replacementIri
                    + "> already exists in the graph. Renaming onto an existing identifier would merge two "
                    + "entities, so no rename was generated.";
        }
        return null;
    }

    private static final class Declarations {
        final Map<String, Set<String>> prefixes = new HashMap<>();
        String problem;
    }

    private record IdentifierResolution(String iri, String error) {}

    private Declarations readTurtleDeclarations(Path file) throws Exception {
        Declarations declarations = new Declarations();
        TurtleLineScanner scanner = new TurtleLineScanner();
        Map<String, String> last = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                scanner.scan(line);
                if (!scanner.prefixes().equals(last)) {
                    for (Map.Entry<String, String> entry : scanner.prefixes().entrySet()) {
                        declarations.prefixes.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>())
                                .add(entry.getValue());
                    }
                    last = new HashMap<>(scanner.prefixes());
                }
            }
        }
        return declarations;
    }

    private Declarations readRdfXmlDeclarations(Path file) throws Exception {
        Declarations declarations = new Declarations();
        RdfXmlScanner scanner = new RdfXmlScanner(new RdfXmlScanner.Listener() {});
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNo = 0;
            while (!scanner.rootSeen() && (line = reader.readLine()) != null) {
                scanner.feed(lineNo++, line);
            }
        }
        if (scanner.unsupportedReason() != null) {
            declarations.problem = "The rdfxml document can't be scanned exactly: " + scanner.unsupportedReason() + ".";
            return declarations;
        }
        for (Map.Entry<String, String> entry : scanner.rootNamespaces().entrySet()) {
            if (!entry.getKey().isEmpty() && !entry.getKey().equals("xml")) {
                declarations.prefixes.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>()).add(entry.getValue());
            }
        }
        return declarations;
    }

    private IdentifierResolution resolveIdentifier(String raw, Declarations declarations, String field) {
        if (raw == null || raw.isBlank()) {
            return new IdentifierResolution(null, field + " is empty.");
        }
        String value = raw.strip();
        String iri;
        if (value.startsWith("<")) {
            if (!value.endsWith(">") || value.length() < 3) {
                return new IdentifierResolution(null, field + " '" + raw + "' is not a well-formed <IRI>.");
            }
            iri = value.substring(1, value.length() - 1);
        } else {
            int colon = value.indexOf(':');
            String prefix = colon < 0 ? null : value.substring(0, colon);
            Set<String> namespaces = prefix == null ? null : declarations.prefixes.get(prefix);
            if (namespaces != null && TurtleNames.isValidPrefix(prefix)) {
                if (namespaces.size() > 1) {
                    return new IdentifierResolution(null, field + " '" + raw + "' uses the prefix '" + prefix
                            + ":', which the document binds to more than one namespace; give the full IRI instead.");
                }
                String local = value.substring(colon + 1);
                if (!TurtleNames.isSimpleLocalName(local)) {
                    return new IdentifierResolution(null, field + " '" + raw
                            + "' is not a well-formed prefixed name; give the full IRI in angle brackets instead.");
                }
                iri = namespaces.iterator().next() + local;
            } else if (looksLikeUndeclaredPrefixedName(value)) {
                return new IdentifierResolution(null, field + " '" + raw + "' looks like a prefixed name, but its "
                        + "prefix is not declared in the document; give the full IRI in angle brackets instead.");
            } else {
                iri = value;
            }
        }
        if (!AssistantGraphIdentifierLookup.isSafeIri(iri) || !isAbsoluteIri(iri)) {
            return new IdentifierResolution(null, field + " '" + raw + "' is not a valid absolute IRI or a "
                    + "prefixed name declared in the document.");
        }
        if (AssistantGraphIdentifierLookup.isBuiltIn(iri)) {
            return new IdentifierResolution(null, field + " <" + iri + "> is in the rdf, rdfs, owl or xsd "
                    + "vocabulary, which can't be renamed.");
        }
        return new IdentifierResolution(iri, null);
    }

    private boolean isAbsoluteIri(String iri) {
        try {
            return new ParsedIRI(iri).isAbsolute() && iri.indexOf(':') < iri.length() - 1;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean looksLikeUndeclaredPrefixedName(String value) {
        int colon = value.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String prefix = value.substring(0, colon);
        String rest = value.substring(colon + 1);
        return TurtleNames.isValidPrefix(prefix) && !rest.startsWith("//")
                && !prefix.equalsIgnoreCase("urn") && !prefix.equalsIgnoreCase("tag")
                && !prefix.equalsIgnoreCase("mailto");
    }

    private RenameScan scanTurtle(Path file, String targetIri, String replacementIri, int maxLines, boolean nTriples)
            throws Exception {
        RenameScan scan = new RenameScan();
        TurtleLineScanner scanner = new TurtleLineScanner();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNo = 0;
            while ((line = reader.readLine()) != null) {
                List<TurtleLineScanner.Token> tokens = scanner.scan(line);
                StringBuilder rewritten = null;
                int copiedUpTo = 0;
                int lineOccurrences = 0;
                for (TurtleLineScanner.Token token : tokens) {
                    if (!token.isTerm()) {
                        continue;
                    }
                    if (token.unresolved()) {
                        scan.problem = "Line " + lineNo + " contains " + token.text() + ", which can't be resolved "
                                + "to a full IRI (" + (token.kind() == TurtleLineScanner.Kind.PNAME
                                ? "undeclared prefix" : "relative IRI with no @base")
                                + "), so the occurrence set can't be derived exactly and no rename was generated.";
                        return scan;
                    }
                    if (replacementIri.equals(token.iri())) {
                        scan.problem = "The replacement <" + replacementIri + "> already appears in the document "
                                + "at line " + lineNo + ", so no rename was generated.";
                        return scan;
                    }
                    if (!targetIri.equals(token.iri())) {
                        continue;
                    }
                    if (rewritten == null) {
                        rewritten = new StringBuilder(line.length() + 32);
                    }
                    rewritten.append(line, copiedUpTo, token.start());
                    rewritten.append(renderTurtle(token, replacementIri, nTriples));
                    copiedUpTo = token.end();
                    lineOccurrences++;
                }
                if (rewritten != null) {
                    rewritten.append(line, copiedUpTo, line.length());
                    if (!scan.addEdit(lineNo, line, rewritten.toString(), lineOccurrences, maxLines)) {
                        return scan;
                    }
                }
                lineNo++;
            }
        }
        return scan;
    }

    private String renderTurtle(TurtleLineScanner.Token token, String replacementIri, boolean nTriples) {
        if (!nTriples && token.kind() == TurtleLineScanner.Kind.PNAME) {
            String namespace = namespaceOf(token);
            if (namespace != null && replacementIri.startsWith(namespace)) {
                String local = replacementIri.substring(namespace.length());
                if (TurtleNames.isSimpleLocalName(local)) {
                    return token.prefix() + ":" + local;
                }
            }
        }
        return "<" + replacementIri + ">";
    }

    private String namespaceOf(TurtleLineScanner.Token token) {
        String text = token.text();
        String local = TurtleNames.unescapeLocalName(text.substring(text.indexOf(':') + 1));
        String iri = token.iri();
        if (iri == null || !iri.endsWith(local)) {
            return null;
        }
        return iri.substring(0, iri.length() - local.length());
    }
}
