package self.research.ontology.owlEditor.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

@Configuration
public class MongoMapKeyConfig {

    private static final String DOT_REPLACEMENT = "\uFF0E";

    private final MappingMongoConverter mappingMongoConverter;

    public MongoMapKeyConfig(MappingMongoConverter mappingMongoConverter) {
        this.mappingMongoConverter = mappingMongoConverter;
    }

    @PostConstruct
    public void configureDotReplacement() {
        mappingMongoConverter.setMapKeyDotReplacement(DOT_REPLACEMENT);
    }
}
