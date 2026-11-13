package self.research.ontology.migrator.support;

import org.bson.Document;
import self.research.ontology.migrator.core.CloseableIterator;
import self.research.ontology.migrator.core.SourceReader;
import self.research.ontology.migrator.core.Values;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InMemorySource implements SourceReader {

    private final Map<String, List<Document>> collections = new HashMap<>();
    private int failAfter = -1;

    public InMemorySource with(String collection, Document... docs) {
        collections.computeIfAbsent(collection, k -> new ArrayList<>()).addAll(List.of(docs));
        return this;
    }

    public InMemorySource with(String collection, List<Document> docs) {
        collections.computeIfAbsent(collection, k -> new ArrayList<>()).addAll(docs);
        return this;
    }

    public InMemorySource failAfterReading(int documents) {
        this.failAfter = documents;
        return this;
    }

    public void stopFailing() {
        this.failAfter = -1;
    }

    @Override
    public boolean exists(String collection) {
        return collections.containsKey(collection);
    }

    @Override
    public CloseableIterator<Document> scan(String collection, Object afterId, int batchSize) {
        List<Document> docs = new ArrayList<>(collections.getOrDefault(collection, List.of()));
        docs.sort(Comparator.comparing(d -> Values.idText(d.get("_id"))));
        String after = afterId == null ? null : Values.idText(afterId);
        List<Document> remaining = docs.stream()
                .filter(d -> after == null || Values.idText(d.get("_id")).compareTo(after) > 0).toList();
        int limit = failAfter;
        List<Document> copy = remaining.stream().map(d -> new Document(d)).toList();
        int[] served = {0};
        CloseableIterator<Document> inner = CloseableIterator.of(copy);
        return new CloseableIterator<>() {
            @Override
            public void close() {
            }

            @Override
            public boolean hasNext() {
                return inner.hasNext();
            }

            @Override
            public Document next() {
                if (limit >= 0 && served[0] >= limit) {
                    throw new IllegalStateException("Simulated connection loss");
                }
                served[0]++;
                return inner.next();
            }
        };
    }

    @Override
    public List<Document> sample(String collection, int size) {
        List<Document> docs = new ArrayList<>(collections.getOrDefault(collection, List.of()));
        return docs.size() <= size ? docs : docs.subList(0, size);
    }

    @Override
    public long count(String collection) {
        return collections.getOrDefault(collection, List.of()).size();
    }
}
