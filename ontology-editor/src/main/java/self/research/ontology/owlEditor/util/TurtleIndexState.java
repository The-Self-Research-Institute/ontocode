package self.research.ontology.owlEditor.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TurtleIndexState {

    private final List<SubjectRangeIndex.Block> blocks = new ArrayList<>();
    private final StringBuilder header = new StringBuilder();
    private final Set<Long> headerLines = new HashSet<>();
    private String openSubject;
    private long openStart = -1;

    void directiveLine(String line, long lineNo) {
        if (headerLines.add(lineNo)) {
            header.append(line).append('\n');
        }
    }

    void open(String subject, long lineNo) {
        openSubject = subject;
        openStart = lineNo;
    }

    void close(long lineNo) {
        if (openStart >= 0) {
            blocks.add(new SubjectRangeIndex.Block(openSubject, openStart, lineNo));
        }
        openSubject = null;
        openStart = -1;
    }

    SubjectRangeIndex finish(Map<String, String> prefixes, boolean terminated) {
        return SubjectRangeIndex.of(blocks, prefixes, header.toString(), "", headerLines, terminated && openStart < 0);
    }
}
