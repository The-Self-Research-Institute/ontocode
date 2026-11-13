package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.TopLevelClassCacheDoc;

import java.util.Optional;

public interface TopLevelClassCacheRepository {

    Optional<TopLevelClassCacheDoc> findById(String projectId);

    void save(TopLevelClassCacheDoc doc);

    void deleteById(String projectId);

    long count();

    void deleteAll();
}
