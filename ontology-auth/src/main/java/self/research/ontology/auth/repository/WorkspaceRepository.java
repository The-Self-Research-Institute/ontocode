package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import self.research.ontology.auth.model.Workspace;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends MongoRepository<Workspace, String> {

    Optional<Workspace> findByWorkspaceId(String workspaceId);

    List<Workspace> findByOwnerId(String ownerId);

    @Query("{ 'members.userId': ?0 }")
    List<Workspace> findByMemberId(String userId);

    @Query("{ $or: [ { 'ownerId': ?0 }, { 'members.userId': ?0 } ] }")
    List<Workspace> findAllUserWorkspaces(String userId);

    boolean existsByWorkspaceId(String workspaceId);
}
