package self.research.ontology.owlEditor.util;

import java.util.ArrayList;
import java.util.List;

public record SubjectBlocks(List<Block> blocks, boolean moreBlocks) {

    public record Block(long startLine, int lineCount, String text, boolean truncated) {}

    public record Limits(int maxBlocks, int maxLinesPerBlock, int maxCharsPerBlock) {}

    static final class Collector {
        private final Limits limits;
        private final List<Builder> blocks = new ArrayList<>();
        private Builder current;
        private boolean moreBlocks;

        Collector(Limits limits) {
            this.limits = limits;
        }

        boolean full() {
            return moreBlocks;
        }

        boolean open() {
            return current != null;
        }

        void start(long startLine, List<String> leadingLines) {
            if (current != null) {
                return;
            }
            Builder last = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
            if (last != null && startLine <= last.endLine()) {
                current = last;
                return;
            }
            if (blocks.size() >= limits.maxBlocks()) {
                moreBlocks = true;
                return;
            }
            current = new Builder(startLine);
            blocks.add(current);
            for (String line : leadingLines) {
                current.append(line, limits);
            }
        }

        void appendLine(String line) {
            if (current != null) {
                current.append(line, limits);
            }
        }

        void close() {
            current = null;
        }

        SubjectBlocks result() {
            List<Block> out = new ArrayList<>(blocks.size());
            for (Builder builder : blocks) {
                out.add(new Block(builder.startLine, builder.lineCount, builder.text.toString(), builder.truncated));
            }
            return new SubjectBlocks(out, moreBlocks);
        }
    }

    private static final class Builder {
        private final long startLine;
        private final StringBuilder text = new StringBuilder();
        private int lineCount;
        private long seenLines;
        private boolean truncated;

        Builder(long startLine) {
            this.startLine = startLine;
        }

        long endLine() {
            return startLine + seenLines - 1;
        }

        void append(String line, Limits limits) {
            seenLines++;
            if (truncated) {
                return;
            }
            int extra = line.length() + (lineCount > 0 ? 1 : 0);
            if (lineCount >= limits.maxLinesPerBlock() || text.length() + extra > limits.maxCharsPerBlock()) {
                truncated = true;
                return;
            }
            if (lineCount > 0) {
                text.append('\n');
            }
            text.append(line);
            lineCount++;
        }
    }
}
