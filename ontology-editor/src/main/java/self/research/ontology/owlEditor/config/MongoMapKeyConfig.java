package self.research.ontology.owlEditor.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

/**
 * Allows MongoDB map keys to contain dots.
 *
 * Several documents (e.g. {@code hierarchy_snapshots.childrenByParent}) use full class IRIs
 * as map keys, and IRIs contain '.'. MongoDB reserves '.' for nested field paths and Spring Data
 * throws a MappingException by default. Enabling a dot replacement makes those saves work.
 *
 * The replacement only transforms keys that actually contain a dot; keys without dots are stored
 * unchanged, so this cannot alter any already-persisted (dot-free) map data. Spring reverses the
 * replacement transparently on read, so callers still see the original IRI keys.
 */
@Configuration
public class MongoMapKeyConfig {

    /** Full-width full stop (U+FF0E) — visually a dot but legal as a MongoDB field name. */
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
