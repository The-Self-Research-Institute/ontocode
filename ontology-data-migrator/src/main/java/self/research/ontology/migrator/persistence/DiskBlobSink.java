package self.research.ontology.migrator.persistence;

import self.research.ontology.common.db.DiskBlobStore;
import self.research.ontology.common.db.StoredFile;
import self.research.ontology.migrator.core.BlobFile;
import self.research.ontology.migrator.core.BlobSink;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Optional;

public final class DiskBlobSink implements BlobSink {

    private final DiskBlobStore store;

    public DiskBlobSink(DiskBlobStore store) {
        this.store = store;
    }

    @Override
    public Optional<Stored> lookup(String id) {
        Optional<StoredFile> info = store.info(id);
        if (info.isEmpty() || !DiskBlobStore.STORAGE.equals(info.get().storage())) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(store.contentPath(info.get().bucket(), id))) {
            return Optional.empty();
        }
        return Optional.of(new Stored(info.get().length(), info.get().sha256()));
    }

    @Override
    public Stored store(BlobFile file, InputStream content) {
        StoredFile stored = store.storeWithId(file.id(), file.bucket(), file.filename(), file.contentType(),
                file.metadata(), file.uploadDate(), content);
        return new Stored(stored.length(), stored.sha256());
    }

    @Override
    public void remove(String id) {
        store.delete(id);
    }
}
