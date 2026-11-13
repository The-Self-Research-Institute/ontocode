package self.research.ontology.migrator.core;

import org.bson.Document;

import java.util.List;

public interface SourceReader {

    boolean exists(String collection);

    CloseableIterator<Document> scan(String collection, Object afterId, int batchSize);

    List<Document> sample(String collection, int size);

    long count(String collection);
}
