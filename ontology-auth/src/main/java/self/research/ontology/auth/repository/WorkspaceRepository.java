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

    @Query("{ 'workspaceId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    Optional<Workspace> findActiveByWorkspaceId(String workspaceId);

    List<Workspace> findByOwnerId(String ownerId);

    @Query("{ 'ownerId': ?0, $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] }")
    List<Workspace> findActiveByOwnerId(String ownerId);

    @Query("{ 'members.userId': ?0 }")
    List<Workspace> findByMemberId(String userId);

    @Query("{ $and: [ { 'members.userId': ?0 }, { $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] } ] }")
    List<Workspace> findActiveByMemberId(String userId);

    @Query("{ $and: [ { 'members.email': ?0 }, { $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] } ] }")
    List<Workspace> findActiveByMemberEmail(String email);

    @Query("{ $or: [ { 'ownerId': ?0 }, { 'members.userId': ?0 } ] }")
    List<Workspace> findAllUserWorkspaces(String userId);

    @Query("{ $and: [ { $or: [ { 'ownerId': ?0 }, { 'members.userId': ?0 } ] }, { $or: [ { 'isDeleted': { $exists: false } }, { 'isDeleted': false } ] } ] }")
    List<Workspace> findAllActiveUserWorkspaces(String userId);

    @Query("{ $and: [ { $or: [ { 'ownerId': ?0 }, { 'members.userId': ?0 } ] }, { 'isDeleted': true } ] }")
    List<Workspace> findDeletedUserWorkspaces(String userId);

    boolean existsByWorkspaceId(String workspaceId);

    Optional<Workspace> findByStripeSubscriptionId(String stripeSubscriptionId);

    Optional<Workspace> findByPendingCheckoutSessionId(String pendingCheckoutSessionId);
}
