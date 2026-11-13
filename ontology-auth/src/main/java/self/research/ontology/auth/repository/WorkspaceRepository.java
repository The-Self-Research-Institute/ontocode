package self.research.ontology.auth.repository;

import self.research.ontology.auth.model.Workspace;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository {

    Optional<Workspace> findById(String id);

    List<Workspace> findAll();

    Workspace save(Workspace workspace);

    void delete(Workspace workspace);

    void deleteAll(Iterable<? extends Workspace> workspaces);

    List<Workspace> findAllByIsDeletedTrueAndDeletedAtBefore(LocalDateTime cutoff);

    Optional<Workspace> findByWorkspaceId(String workspaceId);

    Optional<Workspace> findActiveByWorkspaceId(String workspaceId);

    List<Workspace> findByOwnerId(String ownerId);

    List<Workspace> findActiveByOwnerId(String ownerId);

    List<Workspace> findByMembers_UserId(String userId);

    List<Workspace> findActiveByMemberEmail(String email);

    List<Workspace> findAllUserWorkspaces(String userId);

    List<Workspace> findAllActiveUserWorkspaces(String userId);

    List<Workspace> findDeletedUserWorkspaces(String userId);

    boolean existsByWorkspaceId(String workspaceId);

    Optional<Workspace> findByStripeSubscriptionId(String stripeSubscriptionId);

    Optional<Workspace> findByPendingCheckoutSessionId(String pendingCheckoutSessionId);
}
