package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.ParserConfig;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;

public final class TolerantRdfParsing {

    private TolerantRdfParsing() {
    }

    public static RepositoryConnection apply(RepositoryConnection connection) {
        apply(connection.getParserConfig());
        return connection;
    }

    public static void apply(RDFParser parser) {
        apply(parser.getParserConfig());
    }

    private static void apply(ParserConfig config) {
        config.set(BasicParserSettings.VERIFY_URI_SYNTAX, false);
        config.addNonFatalError(BasicParserSettings.VERIFY_URI_SYNTAX);
    }
}
