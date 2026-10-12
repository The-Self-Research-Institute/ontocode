package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrefixScannerTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void readsTurtlePrefixDeclarationsInBothSyntaxes() throws Exception {
        Path file = write("a.ttl", """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
                @prefix pizza2023: <http://www.semanticweb.org/v0cn037/ontologies/2023/6/PizzaTutorial#> .
                @prefix : <http://example.org/default#> .
                :Pizza a owl:Class .
                """);

        Map<String, String> prefixes = PrefixScanner.scan(file, "turtle");

        assertEquals(Map.of(
                "owl", "http://www.w3.org/2002/07/owl#",
                "xsd", "http://www.w3.org/2001/XMLSchema#",
                "pizza2023", "http://www.semanticweb.org/v0cn037/ontologies/2023/6/PizzaTutorial#",
                "", "http://example.org/default#"), prefixes);
    }

    @Test
    void readsRdfXmlNamespacesFromTheRootTagOnlyEvenWhenTheyRunOverSeveralLines() throws Exception {
        Path file = write("a.owl", """
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://example.org/default#"
                     xmlns:owl="http://www.w3.org/2002/07/owl#"
                     xmlns:ex="http://example.org/ex#">
                    <owl:Class rdf:about="http://example.org/A" xmlns:inner="http://example.org/inner#"/>
                </rdf:RDF>
                """);

        Map<String, String> prefixes = PrefixScanner.scan(file, "rdfxml");

        assertEquals(Map.of(
                "", "http://example.org/default#",
                "owl", "http://www.w3.org/2002/07/owl#",
                "ex", "http://example.org/ex#"), prefixes);
    }

    @Test
    void readsFunctionalAndManchesterDeclarations() throws Exception {
        Path functional = write("a.ofn", "Prefix(ex:=<http://example.org/ex#>)\nPrefix(:=<http://example.org/d#>)\nOntology()\n");
        Path manchester = write("a.omn", "Prefix: ex: <http://example.org/ex#>\nPrefix: : <http://example.org/d#>\nOntology: <x>\n");

        assertEquals(Map.of("ex", "http://example.org/ex#", "", "http://example.org/d#"),
                PrefixScanner.scan(functional, "functional"));
        assertEquals(Map.of("ex", "http://example.org/ex#", "", "http://example.org/d#"),
                PrefixScanner.scan(manchester, "manchester"));
    }

    @Test
    void anUnknownFormatOrMissingFileGivesNoPrefixesInsteadOfFailing() throws Exception {
        assertTrue(PrefixScanner.scan(write("a.nt", "<a> <b> <c> .\n"), "ntriples").isEmpty());
        assertTrue(PrefixScanner.scan(dir.resolve("nope.ttl"), "turtle").isEmpty());
        assertTrue(PrefixScanner.scan(write("b.ttl", "@prefix a: <http://a/> .\n"), null).isEmpty());
    }

    @Test
    void theDifferenceBetweenTwoVersionsIsExactlyTheInsertedPrefix() throws Exception {
        Path before = write("before.ttl", "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n:A a owl:Class .\n");
        Path after = write("after.ttl",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n@prefix pizza2023: <http://ex.org/pizza#> .\n:A a owl:Class .\n");

        Map<String, String> was = PrefixScanner.scan(before, "turtle");
        Map<String, String> now = PrefixScanner.scan(after, "turtle");

        assertEquals(1, now.size() - was.size());
        assertEquals("http://ex.org/pizza#", now.get("pizza2023"));
    }
}
