package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.owlEditor.document.TopLevelClassCacheDoc;

public interface TopLevelClassCacheRepository extends MongoRepository<TopLevelClassCacheDoc, String> {
}
