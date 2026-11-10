package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.ProjectDocument;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ProjectRepository {

    Optional<ProjectDocument> findById(String id);

    boolean existsById(String id);

    ProjectDocument save(ProjectDocument document);

    void deleteById(String id);

    List<ProjectDocument> findAll();

    List<ProjectDocument> findAllByOrderByUpdatedAtDesc();

    Optional<ProjectDocument> findFirstByFilenameAndOwnerEmailOrderByUpdatedAtDesc(String filename, String ownerEmail);

    List<ProjectDocument> findByFilenameAndOwnerEmail(String filename, String ownerEmail);

    Optional<ProjectDocument> findFirstByFilenameOrderByUpdatedAtDesc(String filename);

    List<ProjectDocument> findByStatusIn(Collection<String> statuses);

    List<String> findIdsByImportedIri(String iri);

    void upsertStatus(String id, String status, String statusMessage, String filename, Instant now);

    void upsertMetadata(String id, Map<String, Object> metadata, String statusOnInsert, Instant now);

    void setRequireDraftForMembers(String projectId, String parentProjectId, boolean value, Instant now);

    boolean isRequireDraftForMembers(String parentProjectId);

    void upsertImportProgress(String id, Map<String, Object> progress, String statusMessage, Instant now);

    void upsertOwnerEmail(String id, String ownerEmail, Instant now);

    void upsertBlobId(String id, String blobId, Instant now);

    void upsertProjectMetadata(String id, String status, String statusMessage, String filename, String blobId,
                               String ownerEmail, String ownerId, String workspaceId, String parentProjectId, Instant now);

    void updateStatus(String id, String status, String statusMessage, Instant now);

    long incrementMutationVersion(String id, Instant now);

    long incrementMainGraphRevision(String id, Instant now);
}
