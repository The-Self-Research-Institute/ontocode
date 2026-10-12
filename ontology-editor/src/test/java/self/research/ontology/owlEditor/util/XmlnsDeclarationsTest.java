package self.research.ontology.owlEditor.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlnsDeclarationsTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void declaredButUnusedPrefixesOnTheRootElementAreRecovered() {
        byte[] head = bytes("<rdf:RDF xmlns=\"http://example.org/onto#\" xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
                + " xmlns:pizza=\"http://example.org/pizza#\"><owl:Ontology/>");
        Map<String, String> namespaces = new HashMap<>();

        XmlnsDeclarations.mergeRootDeclarations(namespaces, head, head.length);

        assertEquals("http://example.org/onto#", namespaces.get(""));
        assertEquals("http://example.org/pizza#", namespaces.get("pizza"));
        assertEquals(3, namespaces.size());
    }

    @Test
    void prefixesTheParserAlreadyCapturedAreKept() {
        byte[] head = bytes("<rdf:RDF xmlns:pizza=\"http://other.example/pizza#\">");
        Map<String, String> namespaces = new HashMap<>(Map.of("pizza", "http://example.org/pizza#"));

        XmlnsDeclarations.mergeRootDeclarations(namespaces, head, head.length);

        assertEquals("http://example.org/pizza#", namespaces.get("pizza"));
    }

    @Test
    void onlyTheRootElementIsScannedAndEmptyUrisAreSkipped() {
        byte[] head = bytes("<rdf:RDF xmlns:blank=\"\"><owl:Class xmlns:inner=\"http://example.org/inner#\"/>");
        Map<String, String> namespaces = new HashMap<>();

        XmlnsDeclarations.mergeRootDeclarations(namespaces, head, head.length);

        assertTrue(namespaces.isEmpty());
    }

    @Test
    void nothingToScanLeavesTheMapAlone() {
        Map<String, String> namespaces = new HashMap<>(Map.of("a", "http://a#"));

        XmlnsDeclarations.mergeRootDeclarations(namespaces, null, 10);
        XmlnsDeclarations.mergeRootDeclarations(namespaces, bytes("<x xmlns:b=\"http://b#\">"), 0);

        assertEquals(Map.of("a", "http://a#"), namespaces);
    }
}
