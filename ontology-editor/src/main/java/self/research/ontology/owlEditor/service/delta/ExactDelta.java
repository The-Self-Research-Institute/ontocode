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

    
    public static ExactDelta between(Model before, Model after) {
        Model deleted = new LinkedHashModel(before);
        deleted.removeAll(after);
        Model inserted = new LinkedHashModel(after);
        inserted.removeAll(before);
        return DeltaSparql.withoutRelabelled(new ExactDelta(deleted, inserted));
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
        return out.toString();
    }

    public static Model read(String text) {
        Model model = new LinkedHashModel();
        if (text == null || text.isBlank()) {
            return model;
        }
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
