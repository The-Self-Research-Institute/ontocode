package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RdfExportWriterTest {
    @Test
    void draftRdfXmlPreservesBothAliasesAndUnusedPrefixes() throws Exception {
        var repository = new SailRepository(new MemoryStore());
        var vf = SimpleValueFactory.getInstance();
        var graph = vf.createIRI("urn:test:graph");
        var original = vf.createStatement(vf.createIRI("urn:test:subject"), vf.createIRI("https://example.org/ns#name"),
                vf.createLiteral("Example"));
        var prefixes = Map.of("ex", "https://example.org/ns#", "alias", "https://example.org/ns#",
                "unused", "https://example.org/unused?x=1&y=2#");
        try (var connection = repository.getConnection()) {
            connection.add(original, graph);
            String exported = GraphStatementOps.export(connection, graph.stringValue(), RDFFormat.RDFXML, prefixes);
            var parsed = Rio.parse(new StringReader(exported), "urn:test:base", RDFFormat.RDFXML);
            assertEquals(java.util.Set.of(original), parsed);
            prefixes.forEach((name, uri) -> assertEquals(uri, parsed.getNamespace(name).orElseThrow().getName()));
        } finally {
            repository.shutDown();
        }
    }
}
