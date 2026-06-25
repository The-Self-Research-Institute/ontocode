package self.research.ontology.owlEditor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration("owlEditorJacksonConfig")
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // Serialize all Java 8 time types (LocalDateTime, Instant, etc.) as ISO-8601 strings,
        // not as numeric arrays/epoch values. Mirrors spring.jackson.serialization.write-dates-as-timestamps=false
        // which is overridden by this @Primary bean.
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}