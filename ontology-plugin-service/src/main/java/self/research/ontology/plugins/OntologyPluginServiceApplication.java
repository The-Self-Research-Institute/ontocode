package self.research.ontology.plugins;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@EnableMongoRepositories
public class OntologyPluginServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OntologyPluginServiceApplication.class, args);
    }
}
