package self.research.ontology.common.db;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RoutingBlobStore implements BlobStore {

    private final BlobStore database;
    private final BlobStore disk;
    private final BlobStore writeTarget;
    private final StoredFiles files;

    public RoutingBlobStore(BlobStore database, BlobStore disk, String writeStorage, StoredFiles files) {
        this.database = database;
        this.disk = disk;
        this.files = files;
        if (DiskBlobStore.STORAGE.equals(writeStorage)) {
            this.writeTarget = disk;
        } else if (JdbcBlobStore.STORAGE.equals(writeStorage)) {
            this.writeTarget = database;
        } else {
            throw new IllegalArgumentException("ontocode.blobs.storage must be 'disk' or 'database', not " + writeStorage);
        }
    }

    @Override
    public StoredFile store(String bucket, String filename, String contentType, Map<String, Object> metadata, InputStream content) {
        return writeTarget.store(bucket, filename, contentType, metadata, content);
    }

    @Override
    public Optional<StoredFile> info(String id) {
        return files.info(id);
    }

    @Override
    public InputStream open(String id) {
        return storeFor(id).open(id);
    }

    @Override
    public List<StoredFile> find(String bucket, String filename, Map<String, String> metadataEquals) {
        return files.find(bucket, filename, metadataEquals);
    }

    @Override
    public Optional<StoredFile> findLatest(String bucket, String filename, Map<String, String> metadataEquals) {
        return files.findLatest(bucket, filename, metadataEquals);
    }

    @Override
    public List<StoredFile> recent(String bucket, int limit) {
        return files.recent(bucket, limit);
    }

    @Override
    public boolean delete(String id) {
        Optional<StoredFile> file = files.info(id);
        return file.isPresent() && storeOf(file.get()).delete(id);
    }

    @Override
    public int deleteWhere(String bucket, String filename, Map<String, String> metadataEquals) {
        int removed = 0;
        for (StoredFile file : files.findAllWhere(bucket, filename, metadataEquals)) {
            if (storeOf(file).delete(file.id())) {
                removed++;
            }
        }
        return removed;
    }

    private BlobStore storeFor(String id) {
        StoredFile file = files.info(id).orElseThrow(() -> new IllegalArgumentException("No stored file " + id));
        return storeOf(file);
    }

    private BlobStore storeOf(StoredFile file) {
        return DiskBlobStore.STORAGE.equals(file.storage()) ? disk : database;
    }
}
