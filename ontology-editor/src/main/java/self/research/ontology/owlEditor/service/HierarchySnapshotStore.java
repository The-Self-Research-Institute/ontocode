package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.document.HierarchySnapshotChunkDoc;
import self.research.ontology.owlEditor.document.HierarchySnapshotDoc;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.repository.HierarchySnapshotChunkRepository;
import self.research.ontology.owlEditor.repository.HierarchySnapshotRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Component
public class HierarchySnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(HierarchySnapshotStore.class);

    static final int DEFAULT_INLINE_LIMIT_BYTES = 4 * 1024 * 1024;
    static final int DEFAULT_CHUNK_BYTES = 4 * 1024 * 1024;
    static final int MAX_CHUNKS = 64;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    private final HierarchySnapshotRepository repository;
    private final HierarchySnapshotChunkRepository chunkRepository;

    private int inlineLimitBytes = DEFAULT_INLINE_LIMIT_BYTES;
    private int chunkBytes = DEFAULT_CHUNK_BYTES;

    public HierarchySnapshotStore(HierarchySnapshotRepository repository,
                                  HierarchySnapshotChunkRepository chunkRepository) {
        this.repository = repository;
        this.chunkRepository = chunkRepository;
    }

    public Optional<HierarchySnapshotDoc> findRaw(String projectId) {
        return repository.findById(projectId);
    }

    public Optional<HierarchySnapshotDoc> findById(String projectId) {
        Optional<HierarchySnapshotDoc> raw = repository.findById(projectId);
        if (raw.isEmpty() || raw.get().getChunkSetId() == null) {
            return raw;
        }
        HierarchySnapshotDoc doc = raw.get();
        if (doc.getStatus() != HierarchySnapshotDoc.Status.READY
                && doc.getStatus() != HierarchySnapshotDoc.Status.STALE) {
            return raw;
        }
        try {
            List<HierarchySnapshotChunkDoc> chunks = chunkRepository.findBySetIdOrderByIndexAsc(doc.getChunkSetId());
            if (chunks == null || chunks.size() != doc.getChunkCount()) {
                log.warn("[HierarchyIndex] Snapshot for {} has {}/{} chunks; treating as missing",
                        projectId, chunks == null ? 0 : chunks.size(), doc.getChunkCount());
                return Optional.empty();
            }
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            for (int i = 0; i < chunks.size(); i++) {
                HierarchySnapshotChunkDoc chunk = chunks.get(i);
                if (chunk.getIndex() != i || chunk.getData() == null) {
                    return Optional.empty();
                }
                joined.write(chunk.getData());
            }
            byte[] json;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(joined.toByteArray()))) {
                json = in.readAllBytes();
            }
            Payload payload = MAPPER.readValue(json, Payload.class);
            doc.setTopLevelNodes(payload.topLevelNodes);
            doc.setChildrenByParent(payload.childrenByParent);
            return Optional.of(doc);
        } catch (Exception e) {
            log.warn("[HierarchyIndex] Snapshot chunks for {} unreadable; treating as missing: {}",
                    projectId, e.getMessage());
            return Optional.empty();
        }
    }

    public HierarchySnapshotDoc save(HierarchySnapshotDoc doc) {
        String previousSetId = repository.findById(doc.getProjectId())
                .map(HierarchySnapshotDoc::getChunkSetId).orElse(null);
        boolean hasPayload = doc.getTopLevelNodes() != null || doc.getChildrenByParent() != null;
        if (!hasPayload) {
            HierarchySnapshotDoc saved = repository.save(doc);
            if (previousSetId != null && !previousSetId.equals(doc.getChunkSetId())) {
                deleteChunks(previousSetId);
            }
            return saved;
        }

        byte[] json;
        try {
            Payload payload = new Payload();
            payload.topLevelNodes = doc.getTopLevelNodes();
            payload.childrenByParent = doc.getChildrenByParent();
            json = MAPPER.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new IllegalStateException("Hierarchy snapshot could not be serialised: " + e.getMessage(), e);
        }

        if (json.length <= inlineLimitBytes) {
            doc.setChunkSetId(null);
            doc.setChunkCount(0);
            HierarchySnapshotDoc saved = repository.save(doc);
            if (previousSetId != null) {
                deleteChunks(previousSetId);
            }
            return saved;
        }

        byte[] compressed;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(json);
            }
            compressed = out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Hierarchy snapshot could not be compressed: " + e.getMessage(), e);
        }
        int count = (compressed.length + chunkBytes - 1) / chunkBytes;
        if (count > MAX_CHUNKS) {
            throw new SnapshotTooLargeException("Hierarchy snapshot is too large to store ("
                    + (json.length / (1024 * 1024)) + " MB of hierarchy data)");
        }

        String setId = UUID.randomUUID().toString();
        Date expiresAt = new Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000);
        try {
            for (int i = 0; i < count; i++) {
                byte[] part = Arrays.copyOfRange(compressed, i * chunkBytes,
                        Math.min(compressed.length, (i + 1) * chunkBytes));
                chunkRepository.save(new HierarchySnapshotChunkDoc(setId, i, part, expiresAt));
            }
            HierarchySnapshotDoc stored = copyWithoutPayload(doc);
            stored.setChunkSetId(setId);
            stored.setChunkCount(count);
            repository.save(stored);
        } catch (RuntimeException e) {
            deleteChunks(setId);
            throw e;
        }
        doc.setChunkSetId(setId);
        doc.setChunkCount(count);
        if (previousSetId != null) {
            deleteChunks(previousSetId);
        }
        return doc;
    }

    public void deleteById(String projectId) {
        repository.findById(projectId).map(HierarchySnapshotDoc::getChunkSetId).ifPresent(this::deleteChunks);
        repository.deleteById(projectId);
    }

    private void deleteChunks(String setId) {
        try {
            chunkRepository.deleteBySetId(setId);
        } catch (RuntimeException e) {
            log.warn("[HierarchyIndex] Could not delete old snapshot chunks {}: {}", setId, e.getMessage());
        }
    }

    private static HierarchySnapshotDoc copyWithoutPayload(HierarchySnapshotDoc doc) {
        HierarchySnapshotDoc copy = new HierarchySnapshotDoc();
        copy.setProjectId(doc.getProjectId());
        copy.setStatus(doc.getStatus());
        copy.setAlgorithmVersion(doc.getAlgorithmVersion());
        copy.setRevision(doc.getRevision());
        copy.setBuiltAt(doc.getBuiltAt());
        copy.setErrorMessage(doc.getErrorMessage());
        copy.setTopLevelTotal(doc.getTopLevelTotal());
        copy.setTopLevelComputedLimit(doc.getTopLevelComputedLimit());
        copy.setMeta(doc.getMeta());
        copy.setExpiresAt(doc.getExpiresAt());
        return copy;
    }

    public static class SnapshotTooLargeException extends RuntimeException {
        public SnapshotTooLargeException(String message) {
            super(message);
        }
    }

    static class Payload {
        public List<OntologyDto.TreeNode> topLevelNodes;
        public Map<String, List<OntologyDto.TreeNode>> childrenByParent = new LinkedHashMap<>();
    }
}
