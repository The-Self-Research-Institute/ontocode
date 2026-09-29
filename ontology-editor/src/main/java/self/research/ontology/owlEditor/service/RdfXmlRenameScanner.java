package self.research.ontology.owlEditor.service;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

final class RdfXmlRenameScanner {

    private static final int MAX_BUFFERED_LINES = 10_000;
    private static final long MAX_BUFFERED_CHARS = 8L * 1024 * 1024;

    private RdfXmlRenameScanner() {
    }

    static RenameScan scan(Path file, String targetIri, String replacementIri, int maxLines) throws Exception {
        RenameScan scan = new RenameScan();
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
                    if (!rewriteBufferedLines(scan, bufferedLines, firstBufferedLine, pending, targetIri,
                            replacementIri, maxLines)) {
                        return scan;
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
        if (!scanner.atSafeBoundary()) {
            scan.problem = "The rdfxml document ends inside an unclosed tag or declaration that starts at line "
                    + firstBufferedLine + ", so no rename was generated.";
        }
        return scan;
    }

    private static boolean rewriteBufferedLines(RenameScan scan, List<String> bufferedLines, long firstBufferedLine,
                                                TreeMap<Long, List<RdfXmlScanner.Occurrence>> pending,
                                                String targetIri, String replacementIri, int maxLines) {
        for (int k = 0; k < bufferedLines.size(); k++) {
            long current = firstBufferedLine + k;
            List<RdfXmlScanner.Occurrence> occurrences = pending.remove(current);
            if (occurrences == null) {
                continue;
            }
            if (!rewriteLine(scan, current, bufferedLines.get(k), occurrences, targetIri,
                    replacementIri, maxLines)) {
                return false;
            }
        }
        return true;
    }

    private static boolean rewriteLine(RenameScan scan, long lineNo, String line,
                                       List<RdfXmlScanner.Occurrence> occurrences, String targetIri,
                                       String replacementIri, int maxLines) {
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
            String replacementText = render(occurrence, replacementIri);
            if (replacementText == null) {
                scan.problem = renderProblem(occurrence, lineNo, replacementIri);
                return false;
            }
            rewritten.append(line, copiedUpTo, occurrence.start());
            rewritten.append(replacementText);
            copiedUpTo = occurrence.end();
        }
        rewritten.append(line, copiedUpTo, line.length());
        return scan.addEdit(lineNo, line, rewritten.toString(), matches.size(), maxLines);
    }

    private static String render(RdfXmlScanner.Occurrence occurrence, String replacementIri) {
        switch (occurrence.kind()) {
            case ABOUT, RESOURCE, DATATYPE, TYPE_VALUE -> {
                return RdfXmlScanner.escapeAttribute(replacementIri, occurrence.quote());
            }
            case ID -> {
                int hash = replacementIri.indexOf('#');
                if (occurrence.base() == null || hash < 0) {
                    return null;
                }
                String local = replacementIri.substring(hash + 1);
                if (RdfXmlScanner.isNcName(local)
                        && replacementIri.equals(TurtleNames.resolveAgainst(occurrence.base(), "#" + local))) {
                    return local;
                }
                return null;
            }
            default -> {
                return renderQName(occurrence, replacementIri);
            }
        }
    }

    private static String renderQName(RdfXmlScanner.Occurrence occurrence, String replacementIri) {
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

    private static String renderProblem(RdfXmlScanner.Occurrence occurrence, long lineNo, String replacementIri) {
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
