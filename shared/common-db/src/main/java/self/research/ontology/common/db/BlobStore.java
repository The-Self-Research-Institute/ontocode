package self.research.ontology.common.db;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface BlobStore {

    StoredFile store(String bucket, String filename, String contentType, Map<String, Object> metadata, InputStream content);

    Optional<StoredFile> info(String id);

    InputStream open(String id);

    List<StoredFile> find(String bucket, String filename, Map<String, String> metadataEquals);

    Optional<StoredFile> findLatest(String bucket, String filename, Map<String, String> metadataEquals);

    List<StoredFile> recent(String bucket, int limit);

    boolean delete(String id);

    int deleteWhere(String bucket, String filename, Map<String, String> metadataEquals);
}
