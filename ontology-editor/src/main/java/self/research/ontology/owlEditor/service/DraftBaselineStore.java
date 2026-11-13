package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import self.research.ontology.common.db.BlobStore;
import self.research.ontology.common.db.StoredFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@Component
public class DraftBaselineStore {

    private static final Logger log = LoggerFactory.getLogger(DraftBaselineStore.class);
    private static final String TYPE = "draft-baseline";
    private static final String BUCKET = "fs";

    private final BlobStore blobStore;

    public DraftBaselineStore(BlobStore blobStore) {
        this.blobStore = blobStore;
    }

    public void save(String projectId, String userId, String rdfXml) {
        String filename = filename(projectId, userId);
        StoredFile stored = blobStore.store(BUCKET, filename, "application/rdf+xml",
                Map.of("type", TYPE, "projectId", projectId, "userId", userId),
                new ByteArrayInputStream(rdfXml.getBytes(StandardCharsets.UTF_8)));
        for (StoredFile other : blobStore.find(BUCKET, filename, null)) {
            if (!other.id().equals(stored.id()) && isOlder(other, stored)) {
                blobStore.delete(other.id());
            }
        }
    }

    public Optional<String> load(String projectId, String userId) {
        Optional<StoredFile> file = blobStore.findLatest(BUCKET, filename(projectId, userId), null);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream in = blobStore.open(file.get().id())) {
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.warn("[DRAFT-BASELINE] Could not read stored baseline for project {} user {}: {}",
                    projectId, userId, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String projectId, String userId) {
        blobStore.deleteWhere(BUCKET, filename(projectId, userId), null);
    }

    private static boolean isOlder(StoredFile candidate, StoredFile reference) {
        int byDate = candidate.uploadDate().compareTo(reference.uploadDate());
        return byDate < 0 || (byDate == 0 && candidate.id().compareTo(reference.id()) < 0);
    }

    private static String filename(String projectId, String userId) {
        return TYPE + "/" + projectId + "/" + userId;
    }
}
