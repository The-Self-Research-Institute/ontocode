package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.common.net.ParsedIRI;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditOperation;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Slf4j
@Service
public class AssistantRenameService {

    public static final String RENAME_IDENTIFIER = "rename_identifier";
    public static final String SUPPORTED_FORMATS_TEXT = "turtle, ntriples and rdfxml";

    private static final Set<String> TURTLE_FORMATS = Set.of("turtle", "ttl");
    private static final Set<String> NTRIPLES_FORMATS = Set.of("ntriples", "nt");
    private static final Set<String> RDFXML_FORMATS = Set.of("rdfxml", "xml", "owl");
    private static final int MAX_BUFFERED_LINES = 10_000;
    private static final long MAX_BUFFERED_CHARS = 8L * 1024 * 1024;

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
        if (operation == null || !RENAME_IDENTIFIER.equals(operation.type())) {
            return RenameDerivation.refused("Unsupported operation type '" + (operation == null ? null : operation.type())
                    + "'. The only supported operation is " + RENAME_IDENTIFIER + ".");
        }
        String targetPath = operation.targetPath();
        if (targetPath == null || targetPath.isBlank()) {
            return RenameDerivation.refused("The rename operation has no targetPath.");
        }
        if (!isSupportedFormat(targetPath)) {
            return RenameDerivation.refused("Typed rename is only supported for " + SUPPORTED_FORMATS_TEXT
                    + " documents; occurrences in " + targetPath + " can't be matched exactly, so no rename was "
                    + "generated. Switch the Code View to one of the supported formats, or propose explicit edits.");
        }
        try {
            Path file = storageManager.ensureCodeViewFile(projectId, targetPath);
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
            Set<String> inGraph;
            try {
                inGraph = graphLookup.existing(projectId, List.of(replacement.iri));
            } catch (Exception e) {
                return RenameDerivation.refused("Could not confirm that <" + replacement.iri
                        + "> is unused in the graph (" + e.getMessage() + "), so no rename was generated.");
            }
            if (!inGraph.isEmpty()) {
                return RenameDerivation.refused("The replacement <" + replacement.iri
                        + "> already exists in the graph. Renaming onto an existing identifier would merge two "
                        + "entities, so no rename was generated.");
            }
            Scan scan = rdfXml
                    ? scanRdfXml(file, target.iri, replacement.iri, maxLines)
                    : scanTurtle(file, target.iri, replacement.iri, maxLines, NTRIPLES_FORMATS.contains(
                            targetPath.toLowerCase(Locale.ROOT)));
            if (scan.problem != null) {
                return RenameDerivation.refused(scan.problem);
            }
            if (scan.occurrences == 0) {
                return RenameDerivation.refused("<" + target.iri + "> does not occur in the " + targetPath
                        + " document, so there is nothing to rename.");
            }
            return new RenameDerivation(true, "Found " + scan.occurrences + " occurrence"
                    + (scan.occurrences == 1 ? "" : "s") + " of <" + target.iri + "> on " + scan.edits.size()
                    + " line" + (scan.edits.size() == 1 ? "" : "s") + "; each is renamed to <" + replacement.iri + ">.",
                    target.iri, replacement.iri, scan.edits, scan.occurrences);
        } catch (Exception e) {
            log.warn("[Assistant] Rename derivation failed for project {} targetPath {}: {}",
                    projectId, targetPath, e.getMessage());
            return RenameDerivation.refused("Could not read the " + targetPath + " document to derive the rename ("
                    + e.getMessage() + "), so no rename was generated.");
        }
    }

    private static final class Declarations {
        final Map<String, Set<String>> prefixes = new HashMap<>();
        String problem;
    }

    private record IdentifierResolution(String iri, String error) {}

    private static final class Scan {
        final List<DerivedEdit> edits = new ArrayList<>();
        int occurrences;
        String problem;
    }

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
            if (namespaces != null && TurtleLineScanner.isValidPrefix(prefix)) {
                if (namespaces.size() > 1) {
                    return new IdentifierResolution(null, field + " '" + raw + "' uses the prefix '" + prefix
                            + ":', which the document binds to more than one namespace; give the full IRI instead.");
                }
                String local = value.substring(colon + 1);
                if (!TurtleLineScanner.isSimpleLocalName(local)) {
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
        return TurtleLineScanner.isValidPrefix(prefix) && !rest.startsWith("//")
                && !prefix.equalsIgnoreCase("urn") && !prefix.equalsIgnoreCase("tag")
                && !prefix.equalsIgnoreCase("mailto");
    }

    private Scan scanTurtle(Path file, String targetIri, String replacementIri, int maxLines, boolean nTriples)
            throws Exception {
        Scan scan = new Scan();
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
                    rewritten.append(renderTurtle(token, replacementIri, scanner.prefixes(), nTriples));
                    copiedUpTo = token.end();
                    lineOccurrences++;
                }
                if (rewritten != null) {
                    rewritten.append(line, copiedUpTo, line.length());
                    if (!addEdit(scan, lineNo, line, rewritten.toString(), lineOccurrences, maxLines)) {
                        return scan;
                    }
                }
                lineNo++;
            }
        }
        return scan;
    }

    private String renderTurtle(TurtleLineScanner.Token token, String replacementIri, Map<String, String> prefixes,
                                boolean nTriples) {
        if (!nTriples && token.kind() == TurtleLineScanner.Kind.PNAME) {
            String namespace = prefixes.get(token.prefix());
            if (namespace != null && replacementIri.startsWith(namespace)) {
                String local = replacementIri.substring(namespace.length());
                if (TurtleLineScanner.isSimpleLocalName(local)) {
                    return token.prefix() + ":" + local;
                }
            }
        }
        return "<" + replacementIri + ">";
    }

    private boolean addEdit(Scan scan, long lineNo, String original, String rewritten, int occurrences, int maxLines) {
        if (scan.edits.size() >= maxLines) {
            scan.problem = "This rename touches more than " + maxLines + " lines, which is over the limit for one "
                    + "derived rename group, so no rename was generated.";
            return false;
        }
        scan.edits.add(new DerivedEdit(lineNo, original, rewritten));
        scan.occurrences += occurrences;
        return true;
    }

    private Scan scanRdfXml(Path file, String targetIri, String replacementIri, int maxLines) throws Exception {
        Scan scan = new Scan();
        TreeMap<Long, List<RdfXmlScanner.Occurrence>> pending = new TreeMap<>();
        RdfXmlScanner scanner = new RdfXmlScanner(new RdfXmlScanner.Listener() {
            @Override
            public void occurrence(RdfXmlScanner.Occurrence occurrence) {
                pending.computeIfAbsent(occurrence.line(), k -> new ArrayList<>()).add(occurrence);
            }
        });
        List<String> bufferedLines = new ArrayList<>();
        long bufferedChars = 0;
        long firstBufferedLine = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNo = 0;
            while ((line = reader.readLine()) != null) {
                if (bufferedLines.isEmpty()) {
                    firstBufferedLine = lineNo;
                }
                bufferedLines.add(line);
                bufferedChars += line.length();
                scanner.feed(lineNo, line);
                if (scanner.unsupportedReason() != null) {
                    scan.problem = "The rdfxml document can't be scanned exactly: " + scanner.unsupportedReason()
                            + ", so no rename was generated.";
                    return scan;
                }
                if (scanner.atSafeBoundary()) {
                    for (int k = 0; k < bufferedLines.size(); k++) {
                        long current = firstBufferedLine + k;
                        List<RdfXmlScanner.Occurrence> occurrences = pending.remove(current);
                        if (occurrences == null) {
                            continue;
                        }
                        if (!rewriteRdfXmlLine(scan, current, bufferedLines.get(k), occurrences, targetIri,
                                replacementIri, maxLines)) {
                            return scan;
                        }
                    }
                    bufferedLines.clear();
                    bufferedChars = 0;
                    pending.clear();
                } else if (bufferedLines.size() > MAX_BUFFERED_LINES || bufferedChars > MAX_BUFFERED_CHARS) {
                    scan.problem = "The rdfxml document has a tag or declaration spanning more than "
                            + MAX_BUFFERED_LINES + " lines starting at line " + firstBufferedLine
                            + ", so no rename was generated.";
                    return scan;
                }
                lineNo++;
            }
        }
        return scan;
    }

    private boolean rewriteRdfXmlLine(Scan scan, long lineNo, String line, List<RdfXmlScanner.Occurrence> occurrences,
                                      String targetIri, String replacementIri, int maxLines) {
        List<RdfXmlScanner.Occurrence> matches = new ArrayList<>();
        for (RdfXmlScanner.Occurrence occurrence : occurrences) {
            if (!occurrence.resolvable()) {
                if (occurrence.isName()) {
                    continue;
                }
                scan.problem = "Line " + lineNo + " has the value \"" + occurrence.rawText() + "\", which can't be "
                        + "resolved to a full IRI, so the occurrence set can't be derived exactly and no rename "
                        + "was generated.";
                return false;
            }
            if (replacementIri.equals(occurrence.iri())) {
                scan.problem = "The replacement <" + replacementIri + "> already appears in the document at line "
                        + lineNo + ", so no rename was generated.";
                return false;
            }
            if (targetIri.equals(occurrence.iri())) {
                if (occurrence.multiLine() || occurrence.end() > line.length()) {
                    scan.problem = "An occurrence at line " + lineNo + " spans several lines, so no rename was "
                            + "generated.";
                    return false;
                }
                matches.add(occurrence);
            }
        }
        if (matches.isEmpty()) {
            return true;
        }
        matches.sort(Comparator.comparingInt(RdfXmlScanner.Occurrence::start));
        StringBuilder rewritten = new StringBuilder(line.length() + 32);
        int copiedUpTo = 0;
        for (RdfXmlScanner.Occurrence occurrence : matches) {
            String replacementText = renderRdfXml(occurrence, replacementIri);
            if (replacementText == null) {
                scan.problem = renderProblem(occurrence, lineNo, replacementIri);
                return false;
            }
            rewritten.append(line, copiedUpTo, occurrence.start());
            rewritten.append(replacementText);
            copiedUpTo = occurrence.end();
        }
        rewritten.append(line, copiedUpTo, line.length());
        return addEdit(scan, lineNo, line, rewritten.toString(), matches.size(), maxLines);
    }

    private String renderRdfXml(RdfXmlScanner.Occurrence occurrence, String replacementIri) {
        switch (occurrence.kind()) {
            case ABOUT, RESOURCE, DATATYPE, TYPE_VALUE -> {
                return RdfXmlScanner.escapeAttribute(replacementIri, occurrence.quote());
            }
            case ID -> {
                String prefix = occurrence.base() == null ? null : occurrence.base() + "#";
                if (prefix != null && replacementIri.startsWith(prefix)
                        && RdfXmlScanner.isNcName(replacementIri.substring(prefix.length()))) {
                    return replacementIri.substring(prefix.length());
                }
                return null;
            }
            default -> {
                return renderQName(occurrence, replacementIri);
            }
        }
    }

    private String renderQName(RdfXmlScanner.Occurrence occurrence, String replacementIri) {
        String raw = occurrence.rawText();
        int colon = raw.indexOf(':');
        String originalPrefix = colon < 0 ? "" : raw.substring(0, colon);
        boolean element = occurrence.kind() == RdfXmlScanner.OccurrenceKind.ELEMENT_NAME;
        List<String> candidates = new ArrayList<>();
        candidates.add(originalPrefix);
        occurrence.namespaces().keySet().stream().sorted().filter(p -> !p.equals(originalPrefix)).forEach(candidates::add);
        for (String prefix : candidates) {
            if (prefix.equals("xml") || (prefix.isEmpty() && !element)) {
                continue;
            }
            String namespace = occurrence.namespaces().get(prefix);
            if (namespace == null || namespace.isEmpty() || !replacementIri.startsWith(namespace)) {
                continue;
            }
            String local = replacementIri.substring(namespace.length());
            if (RdfXmlScanner.isNcName(local)) {
                return prefix.isEmpty() ? local : prefix + ":" + local;
            }
        }
        return null;
    }

    private String renderProblem(RdfXmlScanner.Occurrence occurrence, long lineNo, String replacementIri) {
        if (occurrence.kind() == RdfXmlScanner.OccurrenceKind.ID) {
            return "Line " + lineNo + " declares the target with rdf:ID, and <" + replacementIri + "> can't be "
                    + "written as an rdf:ID against the document base, so no rename was generated.";
        }
        return "Line " + lineNo + " uses the target as an XML element or attribute name, and <" + replacementIri
                + "> can't be written as a qualified name with the namespaces declared in this document, so no "
                + "rename was generated. Declare a namespace for it first, or choose a replacement in an existing "
                + "namespace.";
    }
}
