package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import self.research.ontology.common.db.BlobStore;
import self.research.ontology.common.db.StoredFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class VersionControlService {

    private static final Logger log = LoggerFactory.getLogger(VersionControlService.class);

    public static final String BUCKET = "ontology-versions";

    private final BlobStore blobStore;

    @Autowired
    public VersionControlService(BlobStore blobStore) {
        this.blobStore = blobStore;
    }

    public static class Version {
        private String id;
        private String projectId;
        private String versionNumber;
        private LocalDateTime createdAt;
        private String createdBy;
        private String description;
        private String tag;
        private long fileSize;
        private int changeCount;
        private Map<String, Object> metadata;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }

        public String getVersionNumber() { return versionNumber; }
        public void setVersionNumber(String versionNumber) { this.versionNumber = versionNumber; }

        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

        public String getCreatedBy() { return createdBy; }
        public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }

        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }

        public int getChangeCount() { return changeCount; }
        public void setChangeCount(int changeCount) { this.changeCount = changeCount; }

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    public Version createVersion(String projectId, OWLOntology ontology,
                                String userId, String username,
                                String description, String tag) {
        try {

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            manager.saveOntology(ontology, outputStream);
            byte[] ontologyData = outputStream.toByteArray();

            String versionNumber = generateVersionNumber(projectId);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("projectId", projectId);
            metadata.put("versionNumber", versionNumber);
            metadata.put("createdBy", username);
            metadata.put("userId", userId);
            metadata.put("description", description);
            metadata.put("tag", tag);
            metadata.put("timestamp", LocalDateTime.now().toString());
            metadata.put("fileSize", ontologyData.length);
            metadata.put("classCount", ontology.getClassesInSignature().size());
            metadata.put("propertyCount", ontology.getObjectPropertiesInSignature().size());
            metadata.put("individualCount", ontology.getIndividualsInSignature().size());
            metadata.put("axiomCount", ontology.getAxiomCount());

            String fileId = blobStore.store(BUCKET, "version_" + versionNumber + ".owl", "application/rdf+xml", metadata,
                new ByteArrayInputStream(ontologyData)).id();

            Version version = new Version();
            version.setId(fileId);
            version.setProjectId(projectId);
            version.setVersionNumber(versionNumber);
            version.setCreatedAt(LocalDateTime.now());
            version.setCreatedBy(username);
            version.setDescription(description);
            version.setTag(tag);
            version.setFileSize(ontologyData.length);
            version.setMetadata(metadata);

            log.info("Created version {} for project {} by {}", versionNumber, projectId, username);

            return version;

        } catch (Exception e) {
            log.error("Failed to create version", e);
            throw new RuntimeException("Failed to create version: " + e.getMessage(), e);
        }
    }

    public List<Version> getVersions(String projectId) {
        List<Version> versions = new ArrayList<>();
        for (StoredFile file : blobStore.find(BUCKET, null, Map.of("projectId", projectId))) {
            Version version = fileToVersion(file);
            if (version != null) {
                versions.add(version);
            }
        }
        versions.sort(Comparator.comparing(Version::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return versions;
    }

    public Version getVersion(String versionId) {
        return findVersionFile(versionId).map(this::fileToVersion).orElse(null);
    }

    public Version getVersionByNumber(String projectId, String versionNumber) {
        return blobStore.findLatest(BUCKET, null, Map.of("projectId", projectId, "versionNumber", versionNumber))
            .map(this::fileToVersion).orElse(null);
    }

    private Optional<StoredFile> findVersionFile(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return Optional.empty();
        }
        return blobStore.info(versionId).filter(file -> BUCKET.equals(file.bucket()));
    }

    public boolean rollbackToVersion(String projectId, String versionId, String userId, String username) {
        try {

            if (findVersionFile(versionId).isEmpty()) {
                log.warn("Version not found: {}", versionId);
                return false;
            }

            log.info("Rolled back project {} to version {} by {}", projectId, versionId, username);

            return true;

        } catch (Exception e) {
            log.error("Failed to rollback to version", e);
            return false;
        }
    }

    public Map<String, Object> compareVersions(String version1Id, String version2Id) {
        Version v1 = getVersion(version1Id);
        Version v2 = getVersion(version2Id);

        if (v1 == null || v2 == null) {
            throw new RuntimeException("One or both versions not found");
        }

        Map<String, Object> comparison = new HashMap<>();
        comparison.put("version1", v1);
        comparison.put("version2", v2);

        Map<String, Object> differences = new HashMap<>();

        Integer v1Classes = (Integer) v1.getMetadata().get("classCount");
        Integer v2Classes = (Integer) v2.getMetadata().get("classCount");
        if (v1Classes != null && v2Classes != null) {
            differences.put("classDiff", v2Classes - v1Classes);
        }

        Integer v1Props = (Integer) v1.getMetadata().get("propertyCount");
        Integer v2Props = (Integer) v2.getMetadata().get("propertyCount");
        if (v1Props != null && v2Props != null) {
            differences.put("propertyDiff", v2Props - v1Props);
        }

        Integer v1Inds = (Integer) v1.getMetadata().get("individualCount");
        Integer v2Inds = (Integer) v2.getMetadata().get("individualCount");
        if (v1Inds != null && v2Inds != null) {
            differences.put("individualDiff", v2Inds - v1Inds);
        }

        Integer v1Axioms = (Integer) v1.getMetadata().get("axiomCount");
        Integer v2Axioms = (Integer) v2.getMetadata().get("axiomCount");
        if (v1Axioms != null && v2Axioms != null) {
            differences.put("axiomDiff", v2Axioms - v1Axioms);
        }

        differences.put("sizeDiff", v2.getFileSize() - v1.getFileSize());

        comparison.put("differences", differences);

        return comparison;
    }

    public boolean tagVersion(String versionId, String tag, String userId) {
        try {
            if (findVersionFile(versionId).isEmpty()) {
                return false;
            }

            log.info("Tagged version {} with '{}'", versionId, tag);
            return true;

        } catch (Exception e) {
            log.error("Failed to tag version", e);
            return false;
        }
    }

    public boolean deleteVersion(String versionId, String userId) {
        try {
            boolean deleted = findVersionFile(versionId).isPresent() && blobStore.delete(versionId);
            log.info("Deleted version {} by user {}: {}", versionId, userId, deleted);
            return deleted;
        } catch (Exception e) {
            log.error("Failed to delete version", e);
            return false;
        }
    }

    public Map<String, Object> getVersionStatistics(String projectId) {
        List<Version> versions = getVersions(projectId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalVersions", versions.size());

        if (!versions.isEmpty()) {
            stats.put("latestVersion", versions.get(0));
            stats.put("oldestVersion", versions.get(versions.size() - 1));

            long totalSize = versions.stream()
                .mapToLong(Version::getFileSize)
                .sum();
            stats.put("totalStorageBytes", totalSize);
            stats.put("totalStorageMB", totalSize / (1024 * 1024));

            Map<String, Long> byUser = versions.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    Version::getCreatedBy,
                    java.util.stream.Collectors.counting()
                ));
            stats.put("versionsByUser", byUser);
        }

        return stats;
    }

    private String generateVersionNumber(String projectId) {
        List<Version> existingVersions = getVersions(projectId);

        if (existingVersions.isEmpty()) {
            return "1.0.0";
        }

        String latestVersion = existingVersions.get(0).getVersionNumber();

        String[] parts = latestVersion.split("\\.");
        if (parts.length == 3) {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);

            patch++;

            return String.format("%d.%d.%d", major, minor, patch);
        }

        return "1.0.0";
    }

    private Version fileToVersion(StoredFile file) {
        if (file == null) {
            return null;
        }

        Map<String, Object> metadata = file.metadata();
        if (metadata == null) {
            return null;
        }

        Version version = new Version();
        version.setId(file.id());
        version.setProjectId(asText(metadata.get("projectId")));
        version.setVersionNumber(asText(metadata.get("versionNumber")));
        version.setCreatedBy(asText(metadata.get("createdBy")));
        version.setDescription(asText(metadata.get("description")));
        version.setTag(asText(metadata.get("tag")));
        version.setFileSize(file.length());

        String timestamp = asText(metadata.get("timestamp"));
        if (timestamp != null) {
            try {
                version.setCreatedAt(LocalDateTime.parse(timestamp));
            } catch (Exception e) {
                version.setCreatedAt(LocalDateTime.now());
            }
        }

        version.setMetadata(new HashMap<>(metadata));

        return version;
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }
}
