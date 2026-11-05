package self.research.ontology.owlEditor.service;

import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.ArrayList;
import java.util.List;

public final class BulkWrites {

    public static final int MAX_DOCUMENTS_PER_WRITE = 10_000;

    private BulkWrites() {
    }

    public static <T> List<T> saveAll(MongoRepository<T, ?> repository, List<T> documents) {
        if (documents.size() <= MAX_DOCUMENTS_PER_WRITE) {
            return repository.saveAll(documents);
        }
        List<T> saved = new ArrayList<>(documents.size());
        for (int from = 0; from < documents.size(); from += MAX_DOCUMENTS_PER_WRITE) {
            int to = Math.min(from + MAX_DOCUMENTS_PER_WRITE, documents.size());
            saved.addAll(repository.saveAll(documents.subList(from, to)));
        }
        return saved;
    }

    public static <T> void insert(MongoOperations mongo, List<T> documents, Class<?> type) {
        for (int from = 0; from < documents.size(); from += MAX_DOCUMENTS_PER_WRITE) {
            int to = Math.min(from + MAX_DOCUMENTS_PER_WRITE, documents.size());
            mongo.insert(documents.subList(from, to), type);
        }
    }

    public static <T> void insert(MongoOperations mongo, List<T> documents, String collection) {
        for (int from = 0; from < documents.size(); from += MAX_DOCUMENTS_PER_WRITE) {
            int to = Math.min(from + MAX_DOCUMENTS_PER_WRITE, documents.size());
            mongo.insert(documents.subList(from, to), collection);
        }
    }
}
