package self.research.ontology.plugins.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

@Configuration
public class MongoConfig {

    @Bean
    @Qualifier("pluginGridFsTemplate")
    public GridFsTemplate pluginGridFsTemplate(MongoDatabaseFactory mongoDbFactory, MappingMongoConverter mappingMongoConverter) {
        // Plugin binaries live in the dedicated "plugins" bucket (collections: plugins.files, plugins.chunks)
        return new GridFsTemplate(mongoDbFactory, mappingMongoConverter, "plugins");
    }

    @Bean
    @Primary
    @Qualifier("ontologyGridFsTemplate")
    public GridFsTemplate ontologyGridFsTemplate(MongoDatabaseFactory mongoDbFactory, MappingMongoConverter mappingMongoConverter) {
        // Ontology documents stay in the default bucket used by ontology-editor (fs.files / fs.chunks).
        // @Primary ensures editor's services that autowire GridFsTemplate by type get this bean in the
        // merged desktop context (where pluginGridFsTemplate is also present).
        return new GridFsTemplate(mongoDbFactory, mappingMongoConverter);
    }
}
