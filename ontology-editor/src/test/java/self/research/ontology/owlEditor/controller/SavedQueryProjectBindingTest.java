package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import self.research.ontology.owlEditor.model.SparqlQueryEntity;
import self.research.ontology.owlEditor.repository.SparqlQueryRepository;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SavedQueryProjectBindingTest {

    private SparqlQueryRepository repository;
    private SparqlQueryController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SparqlQueryRepository.class);
        controller = new SparqlQueryController(mock(SparqlDatasetService.class), mock(SimpMessagingTemplate.class),
                repository, mock(OntologyMutationService.class));
        SparqlQueryEntity query = new SparqlQueryEntity();
        query.setProjectId("proj-victim");
        when(repository.findById("q1")).thenReturn(Optional.of(query));
    }

    @Test
    void aSavedQueryOfAnotherProjectCannotBeChanged() {
        var response = controller.updateQuery("proj-mine", "q1", new SparqlQueryController.SavedQueryRequest("n", "q"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void aSavedQueryOfAnotherProjectCannotBeDeleted() {
        var response = controller.deleteQuery("proj-mine", "q1");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(repository, never()).deleteById(anyString());
    }

    @Test
    void aSavedQueryOfTheSameProjectCanBeDeleted() {
        var response = controller.deleteQuery("proj-victim", "q1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(repository).deleteById("q1");
    }
}
