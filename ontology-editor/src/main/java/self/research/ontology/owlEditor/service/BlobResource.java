package self.research.ontology.owlEditor.service;

import org.springframework.core.io.AbstractResource;
import self.research.ontology.common.db.BlobStore;
import self.research.ontology.common.db.StoredFile;

import java.io.InputStream;

public class BlobResource extends AbstractResource {

    private final BlobStore store;
    private final StoredFile file;

    public BlobResource(BlobStore store, StoredFile file) {
        this.store = store;
        this.file = file;
    }

    public String getId() {
        return file.id();
    }

    public String getContentType() {
        return file.contentType();
    }

    public StoredFile getStoredFile() {
        return file;
    }

    @Override
    public String getDescription() {
        return "stored file [" + file.id() + "]";
    }

    @Override
    public String getFilename() {
        return file.filename();
    }

    @Override
    public long contentLength() {
        return file.length();
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public InputStream getInputStream() {
        return store.open(file.id());
    }
}
