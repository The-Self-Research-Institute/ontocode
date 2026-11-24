package self.research.ontology.owlEditor.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeepCleanRdfXmlTest {

    private static final String CLEAN = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:owl="http://www.w3.org/2002/07/owl#"
                     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                     xml:base="http://ex.org/t">
              <owl:Ontology rdf:about="http://ex.org/t"/>
              <owl:Class rdf:about="http://ex.org/t#A"/>
              <owl:Class rdf:about="http://ex.org/t#B"><rdfs:subClassOf rdf:resource="http://ex.org/t#A"/></owl:Class>
            </rdf:RDF>
            """;

    private static final String OWL_XML = """
            <?xml version="1.0"?>
            <Ontology xmlns="http://www.w3.org/2002/07/owl#" ontologyIRI="http://ex.org/o">
              <Declaration><Class IRI="http://ex.org/o#A"/></Declaration>
            </Ontology>
            """;

    private static Path write(Path dir, String name, String content) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    @Test
    void cleanRdfXmlPassesTheFusekiParser(@TempDir Path dir) throws Exception {
        assertTrue(RdfXmlStrictCheck.parses(write(dir, "c.owl", CLEAN)));
    }

    @Test
    void owlXmlAndBrokenXmlDoNotPass(@TempDir Path dir) throws Exception {
        assertFalse(RdfXmlStrictCheck.parses(write(dir, "o.owl", OWL_XML)));
        assertFalse(RdfXmlStrictCheck.parses(write(dir, "b.owl", CLEAN.replace("</rdf:RDF>", ""))));
        assertFalse(RdfXmlStrictCheck.parses(write(dir, "t.owl", "@prefix ex: <http://ex.org/> .\nex:a ex:b ex:c .\n")));
    }

    @Test
    void cleanFileIsLeftAsItWasWhenTheOptionIsOn(@TempDir Path dir) throws Exception {
        Path f = write(dir, "c.owl", CLEAN);
        byte[] before = Files.readAllBytes(f);
        OWLFormatConverter.sanitizeFileOnDisk(f, true);
        assertArrayEquals(before, Files.readAllBytes(f));
    }

    @Test
    void cleanFileIsStillRewrittenWhenTheOptionIsOff(@TempDir Path dir) throws Exception {
        Path f = write(dir, "c.owl", CLEAN);
        byte[] before = Files.readAllBytes(f);
        OWLFormatConverter.sanitizeFileOnDisk(f);
        assertFalse(java.util.Arrays.equals(before, Files.readAllBytes(f)));
    }

    @Test
    void owlXmlIsStillConvertedToRdfXmlWhenTheOptionIsOn(@TempDir Path dir) throws Exception {
        Path f = write(dir, "o.owl", OWL_XML);
        OWLFormatConverter.sanitizeFileOnDisk(f, true);
        String after = Files.readString(f);
        assertTrue(after.contains("<rdf:RDF"));
        assertNotEquals(OWL_XML, after);
    }

    @Test
    void fileWithMissingNamespaceStillGetsRepairedWhenTheOptionIsOn(@TempDir Path dir) throws Exception {
        String broken = CLEAN.replace("<owl:Ontology rdf:about=\"http://ex.org/t\"/>",
                "<owl:Ontology rdf:about=\"http://ex.org/t\"><dc:title>T</dc:title></owl:Ontology>");
        Path f = write(dir, "m.owl", broken);
        OWLFormatConverter.sanitizeFileOnDisk(f, true);
        assertTrue(RdfXmlStrictCheck.parses(f));
    }
}
