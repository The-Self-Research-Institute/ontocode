package self.research.ontology.owlEditor.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftChangeTimestampTest {

    @Test
    void timestampSerializesWithAnExplicitUtcMarkerSoClientsDoNotMisreadItAsLocalTime() throws Exception {
        DraftChange draft = new DraftChange("p", "u1", "User", "createClass", null);
        draft.setTimestamp(LocalDateTime.of(2026, 10, 8, 15, 20, 32));

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(draft);

        assertTrue(json.contains("\"timestamp\":\"2026-10-08T15:20:32.000Z\""), json);
    }
}
