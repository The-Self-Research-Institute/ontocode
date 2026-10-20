package self.research.ontology.owlEditor.service.delta;

import self.research.ontology.owlEditor.service.Limit;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

public record ExactDelta(Model deleted, Model inserted) {

    private static final String COMPRESSED = "gz:";
    private static final int COMPRESS_ABOVE_CHARS = 200_000;

    
    public static ExactDelta between(Model before, Model after) {
        Model deleted = new LinkedHashModel(before);
        deleted.removeAll(after);
        Model inserted = new LinkedHashModel(after);
        inserted.removeAll(before);
        return DeltaSparql.withoutRelabelled(new ExactDelta(deleted, inserted));
    }

    public boolean coveredBy(ExactDelta committed) {
        return containsAll(committed.deleted, deleted) && containsAll(committed.inserted, inserted);
    }

    public ExactDelta without(Model accountedDeleted, Model accountedInserted) {
        Model remainingDeleted = new LinkedHashModel();
        deleted.stream().filter(st -> !accountedDeleted.contains(st.getSubject(), st.getPredicate(), st.getObject()))
                .forEach(remainingDeleted::add);
        Model remainingInserted = new LinkedHashModel();
        inserted.stream().filter(st -> !accountedInserted.contains(st.getSubject(), st.getPredicate(), st.getObject()))
                .forEach(remainingInserted::add);
        return new ExactDelta(remainingDeleted, remainingInserted);
    }

    private static boolean containsAll(Model outer, Model inner) {
        return inner.stream().allMatch(st -> outer.contains(st.getSubject(), st.getPredicate(), st.getObject()));
    }

    public boolean isEmpty() {
        return deleted.isEmpty() && inserted.isEmpty();
    }

    public boolean isTooLarge() {
        return deleted.size() + inserted.size() > Limit.MAX_DELTA_TRIPLES.value();
    }

    public static String write(Model model) {
        StringWriter out = new StringWriter();
        Rio.write(model, out, RDFFormat.NTRIPLES);
        String text = out.toString();
        return text.length() > COMPRESS_ABOVE_CHARS ? COMPRESSED + compress(text) : text;
    }

    private static String compress(String text) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bytes)) {
                gzip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Change data could not be compressed", e);
        }
    }

    private static String decompress(String text) {
        try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(text.substring(COMPRESSED.length()))))) {
            return new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Stored change data could not be read", e);
        }
    }

    public static Model read(String stored) {
        Model model = new LinkedHashModel();
        if (stored == null || stored.isBlank()) {
            return model;
        }
        String text = stored.startsWith(COMPRESSED) ? decompress(stored) : stored;
        try {
            RDFParser parser = Rio.createParser(RDFFormat.NTRIPLES);
            parser.getParserConfig().set(BasicParserSettings.PRESERVE_BNODE_IDS, true);
            parser.setRDFHandler(new StatementCollector(model));
            parser.parse(new StringReader(text), "");
        } catch (IOException e) {
            throw new IllegalStateException("Stored change data could not be read", e);
        }
        return model;
    }

    public static ExactDelta of(String deletedText, String insertedText) {
        return new ExactDelta(read(deletedText), read(insertedText));
    }
}
