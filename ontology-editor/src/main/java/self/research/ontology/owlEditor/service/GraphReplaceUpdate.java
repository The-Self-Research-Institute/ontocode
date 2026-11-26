package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

record GraphReplaceUpdate(String text, int triples) {

    static GraphReplaceUpdate of(String graphUri, String rdfContent, RDFFormat format, List<String> graphsToClear) {
        if (rdfContent == null || rdfContent.isBlank()) {
            throw new IllegalArgumentException("RDF content is empty");
        }
        List<Statement> statements = new ArrayList<>();
        RDFParser parser = Rio.createParser(format);
        parser.setRDFHandler(new StatementCollector(statements));
        try (ByteArrayInputStream in = new ByteArrayInputStream(rdfContent.getBytes(StandardCharsets.UTF_8))) {
            parser.parse(in, graphUri);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse RDF content for graph " + graphUri, e);
        }

        StringWriter triplesOut = new StringWriter();
        RDFWriter writer = Rio.createWriter(RDFFormat.NTRIPLES, triplesOut);
        writer.startRDF();
        for (Statement st : statements) {
            writer.handleStatement(st);
        }
        writer.endRDF();

        StringBuilder text = new StringBuilder("CLEAR SILENT GRAPH <").append(graphUri).append(">;\n")
                .append("INSERT DATA { GRAPH <").append(graphUri).append("> {\n")
                .append(triplesOut).append("\n} }");
        for (String graph : graphsToClear) {
            text.append(";\nCLEAR SILENT GRAPH <").append(graph).append(">");
        }
        return new GraphReplaceUpdate(text.toString(), statements.size());
    }
}
