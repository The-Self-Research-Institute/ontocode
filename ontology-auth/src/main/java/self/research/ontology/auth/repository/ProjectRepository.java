package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.Project;

import java.util.List;
import java.util.Optional;

@Repository("authProjectRepository")
public interface ProjectRepository extends MongoRepository<Project, String> {

    Optional<Project> findByProjectId(String projectId);

    List<Project> findAllByProjectId(String projectId);

    @Query("{ 'projectId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    Optional<Project> findActiveByProjectId(String projectId);

    @Query("{ 'projectId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<Project> findAllActiveByProjectId(String projectId);

    List<Project> findByWorkspaceId(String workspaceId);

    @Query("{ 'workspaceId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<Project> findActiveByWorkspaceId(String workspaceId);

    List<Project> findByWorkspaceIdAndStatus(String workspaceId, String status);

    @Query("{ 'workspaceId': ?0, 'status': ?1, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<Project> findActiveByWorkspaceIdAndStatus(String workspaceId, String status);

    List<Project> findByOwnerId(String ownerId);

    @Query("{ 'ownerId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<Project> findActiveByOwnerId(String ownerId);

    List<Project> findByOwnerIdAndStatus(String ownerId, String status);

    List<Project> findByMembers_UserId(String userId);

    @Query("{ 'members.userId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<Project> findActiveByMembers_UserId(String userId);

    boolean existsByProjectId(String projectId);
}
