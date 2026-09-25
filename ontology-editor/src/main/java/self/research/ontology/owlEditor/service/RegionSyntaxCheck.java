package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import self.research.ontology.owlEditor.util.SubjectRangeIndex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// A pass here means the whole spliced file parses; a failure only means the full parse has to decide.
final class RegionSyntaxCheck {

    private RegionSyntaxCheck() {
    }

    static boolean parses(Path sourceFile, String format, List<LineRangeSpliceWriter.SpliceEdit> edits) {
        if (!CodeViewSubjectIndex.supports(format) || edits.isEmpty() || !strictlyOrdered(edits)) {
            return false;
        }
        try {
            return parsesRegion(sourceFile, format, edits);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean parsesRegion(Path sourceFile, String format, List<LineRangeSpliceWriter.SpliceEdit> edits)
            throws IOException {
        Optional<SubjectRangeIndex> index = CodeViewSubjectIndex.forFile(sourceFile, format);
        if (index.isEmpty()) {
            return false;
        }
        List<TriplePatchPlanner.Edit> lineEdits = edits.stream()
                .map(e -> new TriplePatchPlanner.Edit(e.startLine(), e.lineCount(), lineCount(e.newText())))
                .toList();
        Optional<IncrementalSubjectIndex.Region> region = IncrementalSubjectIndex.region(index.get(), lineEdits, format);
        if (region.isEmpty()) {
            return false;
        }
        String fragment = fragment(index.get(), format, splicedRegion(sourceFile, edits, region.get()));
        try (BufferedReader reader = new BufferedReader(new StringReader(fragment))) {
            var parser = Rio.createParser(rdfFormat(format));
            parser.setRDFHandler(new AbstractRDFHandler() {});
            parser.parse(reader, "");
            return true;
        } catch (RDFParseException e) {
            return false;
        }
    }

    private static boolean strictlyOrdered(List<LineRangeSpliceWriter.SpliceEdit> edits) {
        for (int i = 1; i < edits.size(); i++) {
            LineRangeSpliceWriter.SpliceEdit previous = edits.get(i - 1);
            if (edits.get(i).startLine() < previous.startLine() + Math.max(previous.lineCount(), 1)) {
                return false;
            }
        }
        return true;
    }

    private static RDFFormat rdfFormat(String format) {
        if (CodeViewSubjectIndex.isRdfXml(format)) {
            return RDFFormat.RDFXML;
        }
        String f = format.toLowerCase(Locale.ROOT);
        return f.equals("ntriples") || f.equals("nt") ? RDFFormat.NTRIPLES : RDFFormat.TURTLE;
    }

    static int lineCount(String text) {
        return text.isEmpty() ? 0 : text.split("\n", -1).length;
    }

    private static String fragment(SubjectRangeIndex index, String format, String region) {
        String header = index.header().endsWith("\n") || index.header().isEmpty() ? index.header() : index.header() + "\n";
        StringBuilder out = new StringBuilder(header).append(region);
        if (CodeViewSubjectIndex.isRdfXml(format)) {
            out.append(index.footer()).append('\n');
        }
        return out.toString();
    }

    private static String splicedRegion(Path file, List<LineRangeSpliceWriter.SpliceEdit> edits,
                                        IncrementalSubjectIndex.Region region) throws IOException {
        StringBuilder out = new StringBuilder();
        int next = 0;
        long skipUntil = -1;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNo = 0;
            while ((line = reader.readLine()) != null && lineNo <= region.to()) {
                if (lineNo >= region.from()) {
                    if (next < edits.size() && edits.get(next).startLine() == lineNo) {
                        appendText(out, edits.get(next).newText());
                        skipUntil = lineNo + edits.get(next).lineCount();
                        next++;
                    }
                    if (skipUntil <= lineNo) {
                        out.append(line).append('\n');
                    }
                }
                lineNo++;
            }
        }
        for (; next < edits.size(); next++) {
            appendText(out, edits.get(next).newText());
        }
        return out.toString();
    }

    private static void appendText(StringBuilder out, String text) {
        if (!text.isEmpty()) {
            out.append(text).append('\n');
        }
    }
}
