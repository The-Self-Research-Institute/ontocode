package self.research.ontology.owlEditor.repository;

import java.time.Instant;

public interface ProjectWriteLeaseRepository {

    enum Acquisition { ACQUIRED, TOOK_OVER, HELD }

    Acquisition tryAcquire(String projectId, String ownerToken, String holder, Instant now, Instant expiresAt);

    boolean renew(String projectId, String ownerToken, Instant expiresAt);

    boolean release(String projectId, String ownerToken);
}
