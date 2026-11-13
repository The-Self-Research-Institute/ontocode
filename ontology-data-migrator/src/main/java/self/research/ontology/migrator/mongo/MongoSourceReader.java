package self.research.ontology.migrator.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import self.research.ontology.migrator.core.CloseableIterator;
import self.research.ontology.migrator.core.SourceReader;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Sorts.ascending;

public final class MongoSourceReader implements SourceReader, AutoCloseable {

    private final MongoClient client;
    private final MongoDatabase database;

    public MongoSourceReader(String uri, String databaseName) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .readPreference(ReadPreference.secondaryPreferred())
                .applicationName("ontocode-data-migrator")
                .build();
        this.client = MongoClients.create(settings);
        this.database = client.getDatabase(databaseName);
    }

    MongoDatabase database() {
        return database;
    }

    @Override
    public boolean exists(String collection) {
        for (String name : database.listCollectionNames()) {
            if (name.equals(collection)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CloseableIterator<Document> scan(String collection, Object afterId, int batchSize) {
        MongoCollection<Document> source = database.getCollection(collection);
        var find = afterId == null ? source.find() : source.find(ResumeFilter.after(afterId));
        MongoCursor<Document> cursor = find.sort(ascending("_id")).batchSize(batchSize).iterator();
        return new CloseableIterator<>() {
            @Override
            public void close() {
                cursor.close();
            }

            @Override
            public boolean hasNext() {
                return cursor.hasNext();
            }

            @Override
            public Document next() {
                return cursor.next();
            }
        };
    }

    @Override
    public List<Document> sample(String collection, int size) {
        List<Document> out = new ArrayList<>();
        database.getCollection(collection).aggregate(List.of(new Document("$sample", new Document("size", size))))
                .into(out);
        return out;
    }

    @Override
    public long count(String collection) {
        return database.getCollection(collection).estimatedDocumentCount();
    }

    @Override
    public void close() {
        client.close();
    }
}
