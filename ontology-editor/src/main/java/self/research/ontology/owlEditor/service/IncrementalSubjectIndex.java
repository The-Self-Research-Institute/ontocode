package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.util.RdfXmlSubjectBlockReader;
import self.research.ontology.owlEditor.util.SubjectRangeIndex;
import self.research.ontology.owlEditor.util.TurtleSubjectBlockReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class IncrementalSubjectIndex {

    private static final long MAX_REGION_LINES = 200_000;

    private IncrementalSubjectIndex() {
    }

    record Region(long from, long to, long delta) {}

    static Optional<SubjectRangeIndex> derive(SubjectRangeIndex old, List<TriplePatchPlanner.Edit> edits, Path newFile,
                                              String format) throws IOException {
        Optional<Region> region = region(old, edits, format);
        if (region.isEmpty()) {
            return Optional.empty();
        }
        Region r = region.get();
        Optional<List<SubjectRangeIndex.Block>> blocks = indexRegion(old, format, newFile, r.from(), r.to() + r.delta());
        if (blocks.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(merge(old, blocks.get(), r.from(), r.to(), r.delta()));
    }

    static Optional<Region> region(SubjectRangeIndex old, List<TriplePatchPlanner.Edit> edits, String format) {
        if (!old.complete() || edits.isEmpty() || old.blocks().isEmpty() || hasInterleavedDirectives(old)) {
            return Optional.empty();
        }
        long leadingHeaderEnd = old.headerLines().stream()
                .filter(line -> line < old.blocks().get(0).startLine()).mapToLong(Long::longValue).max().orElse(-1);
        long footerLine = CodeViewSubjectIndex.isRdfXml(format)
                ? old.headerLines().stream().mapToLong(Long::longValue).max().orElse(Long.MAX_VALUE)
                : Long.MAX_VALUE;
        long oldFrom = Long.MAX_VALUE;
        long oldTo = Long.MIN_VALUE;
        long delta = 0;
        for (TriplePatchPlanner.Edit edit : edits) {
            long last = edit.startLine() + Math.max(edit.lineCount(), 1) - 1;
            if (edit.startLine() <= leadingHeaderEnd || edit.startLine() > footerLine
                    || (edit.lineCount() > 0 && old.touchesHeader(edit.startLine(), last))) {
                return Optional.empty();
            }
            oldFrom = Math.min(oldFrom, edit.startLine());
            oldTo = Math.max(oldTo, edit.startLine() + edit.lineCount() - 1);
            for (SubjectRangeIndex.Block block : old.overlapping(edit.startLine(), last)) {
                oldFrom = Math.min(oldFrom, block.startLine());
                oldTo = Math.max(oldTo, block.endLine());
            }
            delta += edit.newLineCount() - edit.lineCount();
        }
        if (Math.max(oldTo, oldTo + delta) - oldFrom > MAX_REGION_LINES) {
            return Optional.empty();
        }
        return Optional.of(new Region(oldFrom, oldTo, delta));
    }

    private static boolean hasInterleavedDirectives(SubjectRangeIndex old) {
        if (old.blocks().isEmpty()) {
            return false;
        }
        long firstStart = old.blocks().get(0).startLine();
        long lastEnd = old.blocks().get(old.blocks().size() - 1).endLine();
        return old.headerLines().stream().anyMatch(line -> line > firstStart && line < lastEnd);
    }

    private static Optional<List<SubjectRangeIndex.Block>> indexRegion(SubjectRangeIndex old, String format, Path newFile,
                                                                       long from, long to) throws IOException {
        String header = old.header().endsWith("\n") || old.header().isEmpty() ? old.header() : old.header() + "\n";
        long headerLines = header.chars().filter(c -> c == '\n').count();
        boolean rdfXml = CodeViewSubjectIndex.isRdfXml(format);
        StringBuilder fragment = new StringBuilder(header).append(readLines(newFile, from, to));
        if (rdfXml) {
            fragment.append(old.footer()).append('\n');
        }
        SubjectRangeIndex indexed;
        try (BufferedReader reader = new BufferedReader(new StringReader(fragment.toString()))) {
            indexed = rdfXml ? RdfXmlSubjectBlockReader.index(reader) : TurtleSubjectBlockReader.index(reader);
        }
        long regionEnd = headerLines + (to - from);
        if (!indexed.complete() || indexed.headerLines().stream().anyMatch(line -> line >= headerLines && line <= regionEnd)) {
            return Optional.empty();
        }
        List<SubjectRangeIndex.Block> blocks = new ArrayList<>();
        for (SubjectRangeIndex.Block block : indexed.blocks()) {
            if (block.startLine() < headerLines || block.endLine() > regionEnd) {
                return Optional.empty();
            }
            blocks.add(new SubjectRangeIndex.Block(block.subject(), from + block.startLine() - headerLines,
                    from + block.endLine() - headerLines));
        }
        return Optional.of(blocks);
    }

    private static SubjectRangeIndex merge(SubjectRangeIndex old, List<SubjectRangeIndex.Block> region,
                                           long oldFrom, long oldTo, long delta) {
        List<SubjectRangeIndex.Block> blocks = new ArrayList<>();
        for (SubjectRangeIndex.Block block : old.blocks()) {
            if (block.endLine() < oldFrom) {
                blocks.add(block);
            }
        }
        blocks.addAll(region);
        for (SubjectRangeIndex.Block block : old.blocks()) {
            if (block.startLine() > oldTo) {
                blocks.add(new SubjectRangeIndex.Block(block.subject(), block.startLine() + delta, block.endLine() + delta));
            }
        }
        Set<Long> headerLines = new HashSet<>();
        for (long line : old.headerLines()) {
            headerLines.add(line > oldTo ? line + delta : line);
        }
        return SubjectRangeIndex.of(blocks, old.prefixes(), old.header(), old.footer(), headerLines, true);
    }

    private static String readLines(Path file, long from, long to) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNo = 0;
            while ((line = reader.readLine()) != null && lineNo <= to) {
                if (lineNo >= from) {
                    out.append(line).append('\n');
                }
                lineNo++;
            }
        }
        return out.toString();
    }
}
