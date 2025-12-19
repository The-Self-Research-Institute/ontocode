package self.research.ontology.plugins.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class MongoConfig {

    @Bean
    @Qualifier("pluginGridFsTemplate")
    public GridFsTemplate pluginGridFsTemplate(MongoDatabaseFactory mongoDbFactory, MappingMongoConverter mappingMongoConverter) {
        // Plugin binaries live in the dedicated "plugins" bucket (collections: plugins.files, plugins.chunks)
        return new GridFsTemplate(mongoDbFactory, mappingMongoConverter, "plugins");
    }

    @Bean
    @Qualifier("ontologyGridFsTemplate")
    public GridFsTemplate ontologyGridFsTemplate(MongoDatabaseFactory mongoDbFactory, MappingMongoConverter mappingMongoConverter) {
        // Ontology documents stay in the default bucket used by ontology-editor (fs.files / fs.chunks)
        return new GridFsTemplate(mongoDbFactory, mappingMongoConverter);
    }
}
