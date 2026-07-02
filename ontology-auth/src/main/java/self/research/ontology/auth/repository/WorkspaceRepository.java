package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.Workspace;

import java.util.List;
import java.util.Optional;

@Repository("authWorkspaceRepository")
public interface WorkspaceRepository extends MongoRepository<Workspace, String> {

    Optional<Workspace> findByWorkspaceId(String workspaceId);
    
    // Find workspace excluding soft-deleted ones
    @Query("{ 'workspaceId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    Optional<Workspace> findActiveByWorkspaceId(String workspaceId);

    List<Workspace> findByOwnerId(String ownerId);
    
    // Find workspaces by owner excluding soft-deleted ones
    @Query("{ 'ownerId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<Workspace> findActiveByOwnerId(String ownerId);

    @Query("{ 'members.userId': ?0 }")
    List<Workspace> findByMemberId(String userId);
    
    // Find workspaces by member excluding soft-deleted ones
    @Query("{ $and: [ { 'members.userId': ?0 }, { $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] } ] }")
    List<Workspace> findActiveByMemberId(String userId);

    // Find workspaces where the user is listed by email (legacy/broken membership rows)
    // Excludes soft-deleted workspaces. Used to self-heal missing member.userId mappings.
    @Query("{ $and: [ { 'members.email': ?0 }, { $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] } ] }")
    List<Workspace> findActiveByMemberEmail(String email);

    @Query("{ $or: [ { 'ownerId': ?0 }, { 'members.userId': ?0 } ] }")
    List<Workspace> findAllUserWorkspaces(String userId);
    
    // Find all user workspaces excluding soft-deleted ones
    @Query("{ $and: [ { $or: [ { 'ownerId': ?0 }, { 'members.userId': ?0 } ] }, { $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] } ] }")
    List<Workspace> findAllActiveUserWorkspaces(String userId);
    
    // Find only soft-deleted workspaces for a user
    @Query("{ $and: [ { $or: [ { 'ownerId': ?0 }, { 'members.userId': ?0 } ] }, { 'isDeleted': true } ] }")
    List<Workspace> findDeletedUserWorkspaces(String userId);

    boolean existsByWorkspaceId(String workspaceId);

    Optional<Workspace> findByStripeSubscriptionId(String stripeSubscriptionId);

    Optional<Workspace> findByPendingCheckoutSessionId(String pendingCheckoutSessionId);
}
