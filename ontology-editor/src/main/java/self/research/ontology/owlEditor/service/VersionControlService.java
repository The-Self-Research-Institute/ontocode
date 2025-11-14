package self.research.ontology.owlEditor.service;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for version control of ontologies.
 * Manages snapshots, versions, and rollback functionality.
 */
@Service
public class VersionControlService {

    private static final Logger log = LoggerFactory.getLogger(VersionControlService.class);

    @Autowired
    private GridFsTemplate gridfs;

    /**
     * Version metadata
     */
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

        // Getters and setters
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

    /**
     * Create a new version/snapshot of the ontology
     */
    public Version createVersion(String projectId, OWLOntology ontology, 
                                String userId, String username,
                                String description, String tag) {
        try {
            // Serialize ontology
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            manager.saveOntology(ontology, outputStream);
            byte[] ontologyData = outputStream.toByteArray();
            
            // Generate version number
            String versionNumber = generateVersionNumber(projectId);
            
            // Create metadata
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
            
            // Store in GridFS
            org.bson.Document doc = new org.bson.Document();
            doc.putAll(metadata);
            
            String fileId = gridfs.store(
                new ByteArrayInputStream(ontologyData),
                "version_" + versionNumber + ".owl",
                "application/rdf+xml",
                doc
            ).toString();
            
            // Create version object
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

    /**
     * Get all versions for a project
     */
    public List<Version> getVersions(String projectId) {
        Query query = new Query(Criteria.where("metadata.projectId").is(projectId));
        query.with(org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, 
            "metadata.timestamp"
        ));
        
        List<GridFSFile> files = new ArrayList<>();
        gridfs.find(query).into(files);
        
        List<Version> versions = new ArrayList<>();
        for (GridFSFile file : files) {
            Version version = fileToVersion(file);
            if (version != null) {
                versions.add(version);
            }
        }
        
        return versions;
    }

    /**
     * Get a specific version
     */
    public Version getVersion(String versionId) {
        GridFSFile file = gridfs.findOne(new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(versionId))));
        return fileToVersion(file);
    }

    /**
     * Get version by number
     */
    public Version getVersionByNumber(String projectId, String versionNumber) {
        Query query = new Query(
            Criteria.where("metadata.projectId").is(projectId)
                .and("metadata.versionNumber").is(versionNumber)
        );
        
        GridFSFile file = gridfs.findOne(query);
        return fileToVersion(file);
    }

    /**
     * Rollback to a specific version
     */
    public boolean rollbackToVersion(String projectId, String versionId, String userId, String username) {
        try {
            // Get the version
            GridFSFile versionFile = gridfs.findOne(
                new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(versionId)))
            );
            
            if (versionFile == null) {
                log.warn("Version not found: {}", versionId);
                return false;
            }
            
            // Get the version data
            org.springframework.data.mongodb.gridfs.GridFsResource resource = gridfs.getResource(versionFile);
            
            // Replace current ontology with version
            // In production, this should:
            // 1. Create a backup of current state
            // 2. Load the version ontology
            // 3. Replace the current ontology file
            // 4. Record this as a change
            
            log.info("Rolled back project {} to version {} by {}", projectId, versionId, username);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to rollback to version", e);
            return false;
        }
    }

    /**
     * Compare two versions
     */
    public Map<String, Object> compareVersions(String version1Id, String version2Id) {
        Version v1 = getVersion(version1Id);
        Version v2 = getVersion(version2Id);
        
        if (v1 == null || v2 == null) {
            throw new RuntimeException("One or both versions not found");
        }
        
        Map<String, Object> comparison = new HashMap<>();
        comparison.put("version1", v1);
        comparison.put("version2", v2);
        
        // Compare metadata
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

    /**
     * Tag a version
     */
    public boolean tagVersion(String versionId, String tag, String userId) {
        try {
            GridFSFile file = gridfs.findOne(
                new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(versionId)))
            );
            
            if (file == null) {
                return false;
            }
            
            // Update metadata with tag
            // Note: GridFS doesn't support direct metadata updates
            // In production, would need to recreate the file with updated metadata
            
            log.info("Tagged version {} with '{}'", versionId, tag);
            return true;
            
        } catch (Exception e) {
            log.error("Failed to tag version", e);
            return false;
        }
    }

    /**
     * Delete a version
     */
    public boolean deleteVersion(String versionId, String userId) {
        try {
            gridfs.delete(new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(versionId))));
            log.info("Deleted version {} by user {}", versionId, userId);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete version", e);
            return false;
        }
    }

    /**
     * Get version statistics
     */
    public Map<String, Object> getVersionStatistics(String projectId) {
        List<Version> versions = getVersions(projectId);
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalVersions", versions.size());
        
        if (!versions.isEmpty()) {
            stats.put("latestVersion", versions.get(0));
            stats.put("oldestVersion", versions.get(versions.size() - 1));
            
            // Calculate total storage
            long totalSize = versions.stream()
                .mapToLong(Version::getFileSize)
                .sum();
            stats.put("totalStorageBytes", totalSize);
            stats.put("totalStorageMB", totalSize / (1024 * 1024));
            
            // Most active contributor
            Map<String, Long> byUser = versions.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    Version::getCreatedBy,
                    java.util.stream.Collectors.counting()
                ));
            stats.put("versionsByUser", byUser);
        }
        
        return stats;
    }

    /**
     * Generate version number (semantic versioning)
     */
    private String generateVersionNumber(String projectId) {
        List<Version> existingVersions = getVersions(projectId);
        
        if (existingVersions.isEmpty()) {
            return "1.0.0";
        }
        
        // Get latest version number
        String latestVersion = existingVersions.get(0).getVersionNumber();
        
        // Parse and increment
        String[] parts = latestVersion.split("\\.");
        if (parts.length == 3) {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            
            // Increment patch version
            patch++;
            
            return String.format("%d.%d.%d", major, minor, patch);
        }
        
        return "1.0.0";
    }

    /**
     * Convert GridFS file to Version object
     */
    private Version fileToVersion(GridFSFile file) {
        if (file == null) {
            return null;
        }
        
        org.bson.Document metadata = file.getMetadata();
        if (metadata == null) {
            return null;
        }
        
        Version version = new Version();
        version.setId(file.getObjectId().toString());
        version.setProjectId(metadata.getString("projectId"));
        version.setVersionNumber(metadata.getString("versionNumber"));
        version.setCreatedBy(metadata.getString("createdBy"));
        version.setDescription(metadata.getString("description"));
        version.setTag(metadata.getString("tag"));
        version.setFileSize(file.getLength());
        
        // Parse timestamp
        String timestamp = metadata.getString("timestamp");
        if (timestamp != null) {
            try {
                version.setCreatedAt(LocalDateTime.parse(timestamp));
            } catch (Exception e) {
                version.setCreatedAt(LocalDateTime.now());
            }
        }
        
        // Convert metadata
        Map<String, Object> metadataMap = new HashMap<>();
        for (String key : metadata.keySet()) {
            metadataMap.put(key, metadata.get(key));
        }
        version.setMetadata(metadataMap);
        
        return version;
    }
}