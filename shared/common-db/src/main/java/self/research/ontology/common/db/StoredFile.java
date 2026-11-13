package self.research.ontology.common.db;

import java.time.Instant;
import java.util.Map;

public record StoredFile(
        String id,
        String bucket,
        String filename,
        String contentType,
        long length,
        String sha256,
        Instant uploadDate,
        Map<String, Object> metadata,
        String storage) {
}
