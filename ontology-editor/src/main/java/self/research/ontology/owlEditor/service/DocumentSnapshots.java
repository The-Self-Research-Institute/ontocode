package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

@Component
public class DocumentSnapshots {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public String snapshot(Object entity) {
        if (entity == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(entity);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String idOf(String json) {
        if (json == null) {
            return null;
        }
        JsonNode id = tree(json).get("id");
        return id == null || id.isNull() ? null : id.asText();
    }

    static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("The saved copy of the document is not readable: " + e.getMessage(), e);
        }
    }
}
