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

        return new GridFsTemplate(mongoDbFactory, mappingMongoConverter, "plugins");
    }

    @Bean
    @Primary
    @Qualifier("ontologyGridFsTemplate")
    public GridFsTemplate ontologyGridFsTemplate(MongoDatabaseFactory mongoDbFactory, MappingMongoConverter mappingMongoConverter) {

        return new GridFsTemplate(mongoDbFactory, mappingMongoConverter);
    }
}
