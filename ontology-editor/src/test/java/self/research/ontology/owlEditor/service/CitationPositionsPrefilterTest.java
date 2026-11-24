package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationPositionsPrefilterTest {

    private static final String WITH_CITATION = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:owl="http://www.w3.org/2002/07/owl#">
              <owl:Class rdf:about="http://ex.org/A">
              </owl:Class>
              <rdf:Description rdf:about="urn:citation:c1">
              </rdf:Description>
            </rdf:RDF>
            """;

    @Test
    void fileWithoutAnyCitationMarkerStoresNothing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("plain.owl");
        Files.writeString(file, WITH_CITATION.replace("urn:citation:c1", "http://ex.org/c1"));
        CitationPositions positions = new CitationPositions(project -> dir);

        positions.extractCitationMappingsFromFile(file, "p1");

        assertTrue(positions.getCitationEntityMappings("p1").isEmpty());
        assertFalse(Files.exists(dir.resolve("citation-metadata.json")));
    }

    @Test
    void fileWithACitationStillGetsItsMappingStored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cited.owl");
        Files.writeString(file, WITH_CITATION);
        CitationPositions positions = new CitationPositions(project -> dir);

        positions.extractCitationMappingsFromFile(file, "p1");

        assertEquals("http://ex.org/A", positions.getCitationEntityMappings("p1").get("urn:citation:c1"));
    }
}
