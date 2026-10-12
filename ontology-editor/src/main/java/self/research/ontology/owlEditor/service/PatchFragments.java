package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import self.research.ontology.owlEditor.util.SubjectRangeIndex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
final class PatchFragments {

    private final String format;
    private final String baseUri;

    PatchFragments(String format, String baseUri) {
        this.format = format;
        this.baseUri = baseUri;
    }

    List<String> read(Path file, List<long[]> spans, SubjectRangeIndex index) throws IOException {
        List<long[]> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingLong(span -> span[0]));
        List<StringBuilder> texts = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            texts.add(new StringBuilder());
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNo = 0;
            int cursor = 0;
            while (cursor < sorted.size() && (line = reader.readLine()) != null) {
                while (cursor < sorted.size() && lineNo > sorted.get(cursor)[1]) {
                    cursor++;
                }
                if (cursor < sorted.size() && lineNo >= sorted.get(cursor)[0] && !index.headerLines().contains(lineNo)) {
                    texts.get(cursor).append(line).append('\n');
                }
                lineNo++;
            }
        }
        List<String> result = new ArrayList<>();
        for (StringBuilder text : texts) {
            result.add(text.toString());
        }
        return result;
    }

    boolean hasLabelledBlankNodes(List<String> texts) {
        String marker = CodeViewSubjectIndex.isRdfXml(format) ? "nodeID" : "_:";
        for (String text : texts) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    Model parseAll(List<String> texts, SubjectRangeIndex index) {
        Model model = new LinkedHashModel();
        for (String text : texts) {
            Model parsed = parse(text, index);
            if (parsed == null) {
                return null;
            }
            model.addAll(parsed);
        }
        return model;
    }

    Model parseBlocks(Path file, List<SubjectRangeIndex.Block> blocks, SubjectRangeIndex index) throws IOException {
        List<long[]> spans = new ArrayList<>();
        for (SubjectRangeIndex.Block block : blocks) {
            spans.add(new long[]{block.startLine(), block.endLine()});
        }
        spans.sort(Comparator.comparingLong(span -> span[0]));
        List<long[]> disjoint = new ArrayList<>();
        for (long[] span : spans) {
            if (disjoint.isEmpty() || span[0] > disjoint.get(disjoint.size() - 1)[1]) {
                disjoint.add(span);
            }
        }
        return parseAll(read(file, disjoint, index), index);
    }

    Model parse(String text, SubjectRangeIndex index) {
        String document = CodeViewSubjectIndex.isRdfXml(format)
                ? index.header() + text + "\n" + index.footer()
                : index.header() + text;
        Model model = new LinkedHashModel();
        RDFParser parser = Rio.createParser(rdfFormat());
        parser.getParserConfig().set(BasicParserSettings.VERIFY_URI_SYNTAX, false);
        parser.getParserConfig().set(BasicParserSettings.VERIFY_DATATYPE_VALUES, false);
        parser.getParserConfig().set(BasicParserSettings.NORMALIZE_DATATYPE_VALUES, false);
        parser.getParserConfig().set(BasicParserSettings.FAIL_ON_UNKNOWN_DATATYPES, false);
        parser.getParserConfig().set(BasicParserSettings.FAIL_ON_UNKNOWN_LANGUAGES, false);
        parser.getParserConfig().addNonFatalError(BasicParserSettings.VERIFY_URI_SYNTAX);
        parser.getParserConfig().addNonFatalError(BasicParserSettings.VERIFY_DATATYPE_VALUES);
        parser.getParserConfig().addNonFatalError(BasicParserSettings.VERIFY_LANGUAGE_TAGS);
        parser.setRDFHandler(new StatementCollector(model));
        try {
            parser.parse(new StringReader(document), baseUri);
            return model;
        } catch (Exception e) {
            log.info("[Assistant] Patch fragment did not parse on its own ({}); falling back to a full reimport",
                    e.getMessage());
            return null;
        }
    }

    private RDFFormat rdfFormat() {
        if (CodeViewSubjectIndex.isRdfXml(format)) {
            return RDFFormat.RDFXML;
        }
        String f = format.toLowerCase(Locale.ROOT);
        return f.equals("ntriples") || f.equals("nt") ? RDFFormat.NTRIPLES : RDFFormat.TURTLE;
    }
}
