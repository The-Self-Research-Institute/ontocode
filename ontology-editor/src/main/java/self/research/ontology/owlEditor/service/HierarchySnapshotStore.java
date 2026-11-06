package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.document.HierarchySnapshotDoc;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.repository.HierarchySnapshotRepository;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class HierarchySnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(HierarchySnapshotStore.class);

    static final int DEFAULT_MAX_PAYLOAD_BYTES = 200 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    private final HierarchySnapshotRepository repository;

    private int maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES;

    public HierarchySnapshotStore(HierarchySnapshotRepository repository) {
        this.repository = repository;
    }

    public Optional<HierarchySnapshotDoc> findRaw(String projectId) {
        return repository.findById(projectId);
    }

    public Optional<HierarchySnapshotDoc> findById(String projectId) {
        Optional<HierarchySnapshotRepository.Stored> stored = repository.findWithPayload(projectId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        HierarchySnapshotDoc doc = stored.get().doc();
        if (doc.getStatus() != HierarchySnapshotDoc.Status.READY
                && doc.getStatus() != HierarchySnapshotDoc.Status.STALE) {
            return Optional.of(doc);
        }
        String json = stored.get().payloadJson();
        if (json == null) {
            log.warn("[HierarchyIndex] Snapshot for {} has no stored hierarchy; treating as missing", projectId);
            return Optional.empty();
        }
        try {
            Payload payload = MAPPER.readValue(json, Payload.class);
            doc.setTopLevelNodes(payload.topLevelNodes);
            doc.setChildrenByParent(payload.childrenByParent);
            return Optional.of(doc);
        } catch (IOException e) {
            log.warn("[HierarchyIndex] Snapshot for {} unreadable; treating as missing: {}",
                    projectId, e.getMessage());
            return Optional.empty();
        }
    }

    public HierarchySnapshotDoc save(HierarchySnapshotDoc doc) {
        boolean hasPayload = doc.getTopLevelNodes() != null || doc.getChildrenByParent() != null;
        String json = null;
        if (hasPayload) {
            try {
                Payload payload = new Payload();
                payload.topLevelNodes = doc.getTopLevelNodes();
                payload.childrenByParent = doc.getChildrenByParent();
                json = MAPPER.writeValueAsString(payload);
            } catch (IOException e) {
                throw new IllegalStateException("Hierarchy snapshot could not be serialised: " + e.getMessage(), e);
            }
            if (json.length() > maxPayloadBytes) {
                throw new SnapshotTooLargeException("Hierarchy snapshot is too large to store ("
                        + (json.length() / (1024 * 1024)) + " MB of hierarchy data)");
            }
        }
        repository.save(doc, json);
        return doc;
    }

    public void deleteById(String projectId) {
        repository.deleteById(projectId);
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
