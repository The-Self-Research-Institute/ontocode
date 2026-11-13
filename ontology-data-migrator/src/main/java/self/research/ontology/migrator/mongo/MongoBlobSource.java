package self.research.ontology.migrator.mongo;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.types.ObjectId;
import self.research.ontology.migrator.core.BlobFile;
import self.research.ontology.migrator.core.BlobSource;
import self.research.ontology.migrator.core.CloseableIterator;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Sorts.ascending;

public final class MongoBlobSource implements BlobSource {

    private final MongoSourceReader reader;

    public MongoBlobSource(MongoSourceReader reader) {
        this.reader = reader;
    }

    @Override
    public boolean exists(String bucket) {
        return reader.exists(bucket + ".files");
    }

    @Override
    public CloseableIterator<BlobFile> list(String bucket) {
        MongoCursor<Document> cursor = reader.database().getCollection(bucket + ".files").find()
                .sort(ascending("_id")).iterator();
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
            public BlobFile next() {
                return BlobFile.fromFilesDocument(bucket, cursor.next());
            }
        };
    }

    @Override
    public InputStream open(BlobFile file) {
        GridFSBucket gridfs = GridFSBuckets.create(reader.database(), file.bucket());
        return gridfs.openDownloadStream(toBson(file.rawId()));
    }

    @Override
    public List<BlobFile> sample(String bucket, int size) {
        List<Document> docs = new ArrayList<>();
        reader.database().getCollection(bucket + ".files")
                .aggregate(List.of(new Document("$sample", new Document("size", size)))).into(docs);
        return docs.stream().map(d -> BlobFile.fromFilesDocument(bucket, d)).toList();
    }

    private static BsonValue toBson(Object id) {
        if (id instanceof ObjectId oid) {
            return new BsonObjectId(oid);
        }
        if (id instanceof Integer i) {
            return new BsonInt32(i);
        }
        if (id instanceof Long l) {
            return new BsonInt64(l);
        }
        return new BsonString(String.valueOf(id));
    }
}
