package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SparqlGraphSerializer {

    private SparqlGraphSerializer() {}

    public static RDFFormat formatByName(String name) {
        if (name == null || name.isBlank()) {
            return RDFFormat.TURTLE;
        }
        return switch (name.strip().toLowerCase(Locale.ROOT)) {
            case "turtle", "ttl" -> RDFFormat.TURTLE;
            case "jsonld", "json-ld" -> RDFFormat.JSONLD;
            case "rdfxml", "rdf/xml", "xml" -> RDFFormat.RDFXML;
            case "ntriples", "n-triples", "nt" -> RDFFormat.NTRIPLES;
            default -> throw new SparqlQueryRejectedException(
                    "Unsupported format '" + name + "'; use turtle, jsonld, rdfxml or ntriples.");
        };
    }

    public static RDFFormat formatByAccept(String accept) {
        if (accept == null) {
            return RDFFormat.TURTLE;
        }
        for (String part : accept.split(",")) {
            String type = part.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            switch (type) {
                case "text/turtle":
                    return RDFFormat.TURTLE;
                case "application/ld+json":
                    return RDFFormat.JSONLD;
                case "application/rdf+xml":
                    return RDFFormat.RDFXML;
                case "application/n-triples":
                    return RDFFormat.NTRIPLES;
                default:
                    break;
            }
        }
        return RDFFormat.TURTLE;
    }

    public static String formatName(RDFFormat format) {
        if (format == RDFFormat.JSONLD) {
            return "jsonld";
        }
        if (format == RDFFormat.RDFXML) {
            return "rdfxml";
        }
        if (format == RDFFormat.NTRIPLES) {
            return "ntriples";
        }
        return "turtle";
    }

    public static String serialize(List<Statement> statements, Map<String, String> namespaces, RDFFormat format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RDFWriter writer = Rio.createWriter(format, out);
        writer.startRDF();
        namespaces.forEach(writer::handleNamespace);
        statements.forEach(writer::handleStatement);
        writer.endRDF();
        return out.toString(StandardCharsets.UTF_8);
    }
}
