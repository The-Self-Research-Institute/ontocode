package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeOriginRecordingTest {

    @Test
    void syncedEntriesKeepTheirChangeSetSourceAndAiDetails() {
        HistoryChangeRepository repository = mock(HistoryChangeRepository.class);
        when(repository.existsByProjectIdAndEditId(anyString(), anyString())).thenReturn(false);
        HistorySyncService sync = new HistorySyncService(repository, mock(MongoTemplate.class), mock(OntologyHistoryService.class));
        ChangeOrigin origin = ChangeOrigin.ai("group-7", "claude", "claude-sonnet-5", "session-2",
                "  Add a   PizzaOrder class\nwith a label  ");
        Map<String, Object> data = new HashMap<>();
        data.put("userId", "u@x.com");
        data.put("username", "u@x.com");
        data.put("operationType", "createClass");
        data.put("changeSetId", origin.changeSetId());
        data.put("source", origin.source());
        data.put("ai", origin.ai());
        data.put("subChanges", List.of(Map.of("predicate", RDFS.LABEL.stringValue(), "newValue", "PizzaOrder", "addition", "true")));

        sync.syncChange("p", "edit-1", data);

        ArgumentCaptor<HistoryChange> saved = ArgumentCaptor.forClass(HistoryChange.class);
        verify(repository).save(saved.capture());
        HistoryChange change = saved.getValue();
        assertEquals("group-7", change.getChangeSetId());
        assertEquals(ChangeOrigin.AI, change.getSource());
        assertEquals("claude-sonnet-5", change.getAi().getModel());
        assertEquals("Add a PizzaOrder class with a label", change.getAi().getSummary());
        assertNotNull(change.getSubChanges().get(0).getId());
    }

    @Test
    void syncedTimestampsAreStoredAsUtcRegardlessOfServerTimezone() {
        HistoryChangeRepository repository = mock(HistoryChangeRepository.class);
        when(repository.existsByProjectIdAndEditId(anyString(), anyString())).thenReturn(false);
        HistorySyncService sync = new HistorySyncService(repository, mock(MongoTemplate.class), mock(OntologyHistoryService.class));
        Instant instant = Instant.parse("2026-10-08T15:20:32Z");
        Map<String, Object> data = new HashMap<>();
        data.put("userId", "u@x.com");
        data.put("username", "u@x.com");
        data.put("operationType", "createClass");
        data.put("timestamp", instant.toEpochMilli());

        sync.syncChange("p", "edit-1", data);

        ArgumentCaptor<HistoryChange> saved = ArgumentCaptor.forClass(HistoryChange.class);
        verify(repository).save(saved.capture());
        assertEquals("2026-10-08T15:20:32Z", saved.getValue().getTimestampIso());
    }

    @Test
    void codeViewRecorderStampsEveryEntryWithTheSameOrigin() {
        OntologyHistoryService history = mock(OntologyHistoryService.class);
        CodeViewHistoryRecorder recorder = new CodeViewHistoryRecorder(history, mock(DraftTrackingService.class));
        ValueFactory vf = SimpleValueFactory.getInstance();
        Model added = new LinkedHashModel();
        added.add(vf.createIRI("http://example.org/A"), RDF.TYPE, OWL.CLASS);
        added.add(vf.createIRI("http://example.org/A"), RDFS.LABEL, vf.createLiteral("A"));
        added.add(vf.createIRI("http://example.org/B"), RDFS.COMMENT, vf.createLiteral("b"));
        ChangeOrigin origin = ChangeOrigin.ai("group-7", "claude", "claude-sonnet-5", "session-2", "Edit");

        recorder.record("p", "u@x.com", "u@x.com", new LinkedHashModel(), added, false, origin);

        verify(history).recordEdit(eq("p"), eq("u@x.com"), eq("u@x.com"), eq("createClass"), eq("http://example.org/A"),
                any(), any(), any(), anyString(), any(), any(), anyBoolean(), eq(origin));
        verify(history).recordEdit(eq("p"), eq("u@x.com"), eq("u@x.com"), eq("addStatement"), eq("http://example.org/B"),
                any(), any(), any(), anyString(), any(), any(), anyBoolean(), eq(origin));
    }
}
