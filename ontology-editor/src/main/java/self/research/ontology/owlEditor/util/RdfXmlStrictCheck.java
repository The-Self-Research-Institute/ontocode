package self.research.ontology.owlEditor.util;

import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ErrorHandler;
import org.apache.jena.riot.system.StreamRDFLib;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RdfXmlStrictCheck {

    private static final Logger log = LoggerFactory.getLogger(RdfXmlStrictCheck.class);

    private static final ErrorHandler FAIL_ON_ERRORS = new ErrorHandler() {
        @Override
        public void warning(String message, long line, long col) {
            if (message != null && message.contains("IRI")) {
                throw new RiotException(message);
            }
        }

        @Override
        public void error(String message, long line, long col) {
            throw new RiotException(message);
        }

        @Override
        public void fatal(String message, long line, long col) {
            throw new RiotException(message);
        }
    };

    private RdfXmlStrictCheck() {
    }

    public static boolean parses(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            RDFParser.create().source(in).lang(Lang.RDFXML).errorHandler(FAIL_ON_ERRORS)
                    .parse(StreamRDFLib.sinkNull());
            return true;
        } catch (Exception e) {
            log.info("{} is not clean RDF/XML for the Fuseki parser ({}); keeping the full repair", file.getFileName(), e.getMessage());
            return false;
        }
    }
}
