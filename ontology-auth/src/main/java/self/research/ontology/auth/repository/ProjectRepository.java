package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import self.research.ontology.auth.model.Project;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends MongoRepository<Project, String> {
    
    Optional<Project> findByProjectId(String projectId);
    
    // Find project excluding soft-deleted ones
    @Query("{ 'projectId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    Optional<Project> findActiveByProjectId(String projectId);
    
    List<Project> findByWorkspaceId(String workspaceId);
    
    // Find projects by workspace excluding soft-deleted ones
    @Query("{ 'workspaceId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<Project> findActiveByWorkspaceId(String workspaceId);
    
    List<Project> findByWorkspaceIdAndStatus(String workspaceId, String status);
    
    // Find active projects by workspace and status excluding soft-deleted ones
    @Query("{ 'workspaceId': ?0, 'status': ?1, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<Project> findActiveByWorkspaceIdAndStatus(String workspaceId, String status);
    
    List<Project> findByOwnerId(String ownerId);
    
    // Find projects by owner excluding soft-deleted ones
    @Query("{ 'ownerId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<Project> findActiveByOwnerId(String ownerId);
    
    List<Project> findByOwnerIdAndStatus(String ownerId, String status);
    
    // Find projects where user is a member
    List<Project> findByMembers_UserId(String userId);
    
    // Find active projects where user is a member excluding soft-deleted ones
    @Query("{ 'members.userId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<Project> findActiveByMembers_UserId(String userId);
    
    boolean existsByProjectId(String projectId);
}
