package self.research.ontology.owlEditor.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RdfXmlIndexState {

    private final List<SubjectRangeIndex.Block> blocks = new ArrayList<>();
    private final StringBuilder header = new StringBuilder();
    private final Set<Long> headerLines = new HashSet<>();
    private String footer = "";
    private String openSubject;
    private long openStart = -1;

    void header(String markup, long fromLine, long toLine) {
        header.append(markup).append('\n');
        for (long line = fromLine; line <= toLine; line++) {
            headerLines.add(line);
        }
    }

    void footer(String markup, long lineNo) {
        footer = markup;
        headerLines.add(lineNo);
    }

    void openBlock(String subject, long lineNo) {
        openSubject = subject;
        openStart = lineNo;
    }

    void closeBlock(long lineNo) {
        if (openStart >= 0) {
            blocks.add(new SubjectRangeIndex.Block(openSubject, openStart, lineNo));
        }
        openSubject = null;
        openStart = -1;
    }

    SubjectRangeIndex finish(Map<String, String> namespaces, boolean complete) {
        return SubjectRangeIndex.of(blocks, namespaces, header.toString(), footer, headerLines,
                complete && openStart < 0 && !footer.isEmpty());
    }

    static String subjectOf(String base, String about, String rdfId, String nodeId) {
        if (about != null) {
            return resolveAgainstBase(base, about);
        }
        if (rdfId != null) {
            return base == null ? null : stripFragment(base) + "#" + rdfId;
        }
        return nodeId != null ? "_:" + nodeId : "[]";
    }

    private static String resolveAgainstBase(String base, String about) {
        if (about.contains(":") && !about.startsWith("#")) {
            return about;
        }
        if (base == null) {
            return null;
        }
        if (about.startsWith("#")) {
            return stripFragment(base) + about;
        }
        try {
            return URI.create(base).resolve(about).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripFragment(String iri) {
        int hash = iri.indexOf('#');
        return hash >= 0 ? iri.substring(0, hash) : iri;
    }
}
