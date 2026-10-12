package self.research.ontology.owlEditor.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record SubjectRangeIndex(List<Block> blocks, Map<String, List<Block>> bySubject, Map<String, String> prefixes,
                                String header, String footer, Set<Long> headerLines, boolean complete) {

    public record Block(String subject, long startLine, long endLine) {
        public boolean isBlankSubject() {
            return subject == null || subject.startsWith("_:") || subject.equals("[]") || subject.equals("()");
        }

        public boolean overlaps(long fromLine, long toLine) {
            return startLine <= toLine && endLine >= fromLine;
        }
    }

    public static SubjectRangeIndex of(List<Block> blocks, Map<String, String> prefixes, String header, String footer,
                                       Set<Long> headerLines, boolean complete) {
        Map<String, List<Block>> bySubject = new HashMap<>();
        for (Block block : blocks) {
            if (block.subject() != null) {
                bySubject.computeIfAbsent(block.subject(), key -> new ArrayList<>()).add(block);
            }
        }
        return new SubjectRangeIndex(List.copyOf(blocks), Collections.unmodifiableMap(bySubject), Map.copyOf(prefixes),
                header, footer, Set.copyOf(headerLines), complete);
    }

    public List<Block> blocksFor(String subject) {
        return bySubject.getOrDefault(subject, List.of());
    }

    public List<Block> overlapping(long fromLine, long toLine) {
        List<Block> found = new ArrayList<>();
        int low = 0;
        int high = blocks.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (blocks.get(mid).endLine() < fromLine) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        for (int i = Math.max(0, low - 1); i < blocks.size() && blocks.get(i).startLine() <= toLine; i++) {
            if (blocks.get(i).overlaps(fromLine, toLine)) {
                found.add(blocks.get(i));
            }
        }
        return found;
    }

    public boolean touchesHeader(long fromLine, long toLine) {
        for (long line = fromLine; line <= toLine; line++) {
            if (headerLines.contains(line)) {
                return true;
            }
        }
        return false;
    }
}
