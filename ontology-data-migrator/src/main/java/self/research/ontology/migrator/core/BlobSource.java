package self.research.ontology.migrator.core;

import java.io.InputStream;
import java.util.List;

public interface BlobSource {

    boolean exists(String bucket);

    CloseableIterator<BlobFile> list(String bucket);

    InputStream open(BlobFile file);

    List<BlobFile> sample(String bucket, int size);
}
