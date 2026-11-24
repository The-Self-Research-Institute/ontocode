package self.research.ontology.owlEditor.util;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RdfXmlDefaultsTest {

    private static final String PROTEGE_STYLE = """
        <?xml version="1.0"?>
        <!DOCTYPE rdf:RDF [
            <!ENTITY owl "http://www.w3.org/2002/07/owl#" >
            <!ENTITY rdfs "http://www.w3.org/2000/01/rdf-schema#" >
        ]>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                 xmlns:owl="&owl;" xmlns:rdfs="&rdfs;">
            <owl:Class rdf:about="http://ex.org/A">
                <rdfs:label>A</rdfs:label>
            </owl:Class>
        </rdf:RDF>
        """;

    @BeforeAll
    static void defaults() {
        RdfXmlDefaults.apply();
    }

    @Test
    void theDefaultAllowsAnInternalDoctypeLikeProtegeWrites() throws Exception {
        assertEquals("false", System.getProperty(RdfXmlDefaults.DISALLOW_DOCTYPE));

        Model model = Rio.parse(new StringReader(PROTEGE_STYLE), "", RDFFormat.RDFXML);

        assertEquals(2, model.size());
    }

    @Test
    void anExternalEntityIsNeverReadIntoTheModel(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-VALUE");
        String hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE rdf:RDF [ <!ENTITY leak SYSTEM "%s" > ]>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                <rdf:Description rdf:about="http://ex.org/A"><rdfs:label>&leak;</rdfs:label></rdf:Description>
            </rdf:RDF>
            """.formatted(secret.toUri());

        String seen;
        try {
            Model model = Rio.parse(new StringReader(hostile), "", RDFFormat.RDFXML);
            seen = model.stream().map(Statement::getObject).map(Object::toString).reduce("", String::concat);
        } catch (Exception refused) {
            seen = String.valueOf(refused.getMessage());
        }

        assertFalse(seen.contains("TOP-SECRET-VALUE"));
    }
}
