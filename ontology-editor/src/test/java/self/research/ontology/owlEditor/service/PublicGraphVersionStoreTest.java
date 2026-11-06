package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.repository.JdbcPublicGraphVersionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicGraphVersionStoreTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final PublicGraphVersionStore store = new PublicGraphVersionStore(new JdbcPublicGraphVersionRepository(jdbc));

    @BeforeEach
    void clean() {
        jdbc.update("delete from project_public_graph_version");
    }

    @Test
    void incrementCreatesTheCounterAndReturnsEachNewValue() {
        assertEquals(1L, store.increment("proj-1"));
        assertEquals(2L, store.increment("proj-1"));
        assertEquals(1L, store.increment("proj-2"));
    }

    @Test
    void readReturnsPersistedValueOrZero() {
        store.increment("proj-1");
        store.increment("proj-1");

        assertEquals(2L, store.read("proj-1"));
        assertEquals(0L, store.read("proj-2"));
    }
}
