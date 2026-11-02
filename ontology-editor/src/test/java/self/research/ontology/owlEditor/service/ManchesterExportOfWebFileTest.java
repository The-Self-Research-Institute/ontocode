package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManchesterExportOfWebFileTest {

    private static List<String> general(OWLOntology ontology) {
        return new TreeSet<>(GeneralClassAxioms.list(ontology).stream()
                .map(GeneralClassAxioms.Entry::value)
                .collect(Collectors.toList())).stream().toList();
    }

    @Test
    void manchesterExportOfAWebExportedFileLoadsBackWithEveryGeneralClassAxiom() throws Exception {
        String rdfXml;
        try (InputStream in = getClass().getResourceAsStream("/gca/web-export.owl")) {
            rdfXml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        OWLOntology original = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new ByteArrayInputStream(rdfXml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(7, general(original).size());
        String manchester = new OntologyExporter(null, null, null).convertRdfXmlToOwlApiFormat(rdfXml, "manchester");
        OWLOntology reloaded = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new ByteArrayInputStream(manchester.getBytes(StandardCharsets.UTF_8)));
        assertTrue(general(reloaded).containsAll(general(original)));
        assertEquals(general(original), general(reloaded));
    }
}
