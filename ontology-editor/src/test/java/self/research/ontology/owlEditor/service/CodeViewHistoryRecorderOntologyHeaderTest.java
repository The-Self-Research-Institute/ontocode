package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CodeViewHistoryRecorderOntologyHeaderTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI ONTOLOGY = VF.createIRI("http://example.org/pizza/PizzaTutorial");
    private static final IRI CLASS = VF.createIRI("http://example.org/pizza/Pizza");

    private static Model header() {
        Model model = new LinkedHashModel();
        model.add(ONTOLOGY, RDF.TYPE, OWL.ONTOLOGY);
        return model;
    }

    @Test
    void anAnnotationAddedToTheOntologyHeaderIsNamedAfterTheAnnotationNotTheOntology() {
        OntologyHistoryService history = mock(OntologyHistoryService.class);
        CodeViewHistoryRecorder recorder = new CodeViewHistoryRecorder(history, mock(DraftTrackingService.class));
        Model before = header();
        Model after = header();
        after.add(ONTOLOGY, OWL.PRIORVERSION, VF.createLiteral("test"));

        recorder.record("p", "u@x.com", "u@x.com", before, after, false, null, false);

        verify(history).recordEdit(eq("p"), eq("u@x.com"), eq("u@x.com"), eq("addStatement"), eq(ONTOLOGY.stringValue()),
                eq("ontology annotation priorVersion"), any(), any(), contains("Ontology header"), any(), any(),
                anyBoolean(), any());
    }

    @Test
    void aChangeToAnOrdinaryClassKeepsItsOwnLabel() {
        OntologyHistoryService history = mock(OntologyHistoryService.class);
        CodeViewHistoryRecorder recorder = new CodeViewHistoryRecorder(history, mock(DraftTrackingService.class));
        Model before = new LinkedHashModel();
        before.add(CLASS, RDF.TYPE, OWL.CLASS);
        Model after = new LinkedHashModel(before);
        after.add(CLASS, RDFS.COMMENT, VF.createLiteral("a pizza"));

        recorder.record("p", "u@x.com", "u@x.com", before, after, false, null, false);

        verify(history).recordEdit(anyString(), anyString(), anyString(), anyString(), eq(CLASS.stringValue()),
                eq("Pizza"), any(), any(), contains("Code View save modified"), any(), any(), anyBoolean(), any());
    }
}
