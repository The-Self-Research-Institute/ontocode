package self.research.ontology.reasoner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import self.research.ontology.reasoner.service.ReasoningJobStore;
import self.research.ontology.reasoner.service.ReasoningSnapshotStore;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = ReasonerWorkerApplication.class, properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none?connectTimeout=1&socketTimeout=2",
        "spring.datasource.username=nobody",
        "spring.datasource.password=nothing",
        "spring.datasource.hikari.connection-timeout=1000"
})
class DatabaseDownStartupTest {

    @Autowired
    ReasoningJobStore jobStore;

    @Autowired
    ReasoningSnapshotStore snapshotStore;

    @Test
    void theWorkerStartsWithoutTheDatabaseAndTheStoresDegradeToMemoryOnly() {
        assertNotNull(jobStore);
        assertNotNull(snapshotStore);

        assertEquals(Optional.empty(), jobStore.find("any-job"));
        assertEquals(Optional.empty(), snapshotStore.find("P1", 3, 900, "HERMIT", "none"));
        snapshotStore.save("P1", 3, 900, "HERMIT", "none", Map.of("consistent", true));
    }
}
