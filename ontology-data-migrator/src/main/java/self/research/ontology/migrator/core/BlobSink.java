package self.research.ontology.migrator.core;

import java.io.InputStream;
import java.util.Optional;

public interface BlobSink {

    record Stored(long length, String sha256) {
    }

    Optional<Stored> lookup(String id);

    Stored store(BlobFile file, InputStream content);

    void remove(String id);
}
