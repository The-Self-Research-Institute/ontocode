package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.document.WorkspaceDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.repository.WorkspaceRepository;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Resolves workspace ownership for FREE-plan enforcement.
 * File-level ontology projects use hierarchical IDs {@code proj-...--fileId}; the parent
 * {@code proj-...} row may hold {@code workspaceId} when the child document does not.
 */
@Service
public class WorkspaceOwnershipService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceOwnershipService.class);

    private final ProjectRepository projectRepository;
    private final WorkspaceRepository workspaceRepository;
    private final MongoTemplate mongoTemplate;

    public WorkspaceOwnershipService(ProjectRepository projectRepository,
                                     WorkspaceRepository workspaceRepository,
                                     MongoTemplate mongoTemplate) {
        this.projectRepository = projectRepository;
        this.workspaceRepository = workspaceRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Resolve workspaceId for a single project id (tries composite id, then parent before first "--").
     * Fail-soft on repository errors (same as legacy {@code CollaborativeEditController} helper).
     */
    public Optional<String> resolveWorkspaceIdForProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<ProjectDocument> doc = projectRepository.findById(projectId);
            if (doc.isPresent()) {
                String ws = doc.get().getWorkspaceId();
                if (ws != null && !ws.isBlank()) {
                    return Optional.of(ws.trim());
                }
            }
            if (!projectId.contains("--")) {
                return Optional.empty();
            }
            int sep = projectId.indexOf("--");
            String parentId = sep > 0 ? projectId.substring(0, sep) : "";
            // Avoid useless lookups / loops for malformed ids (e.g. "--x", or empty parent)
            if (parentId.isBlank() || parentId.equals(projectId)) {
                return Optional.empty();
            }
            doc = projectRepository.findById(parentId);
            if (doc.isPresent()) {
                String ws = doc.get().getWorkspaceId();
                if (ws != null && !ws.isBlank()) {
                    return Optional.of(ws.trim());
                }
            }
        } catch (Exception e) {
            log.debug("resolveWorkspaceIdForProject failed for projectId={}: {}", projectId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * True if userId matches workspace owner. Repository failures → false (deny FREE bypass).
     */
    public boolean isUserOwnerOfWorkspace(String userId, String workspaceId) {
        if (userId == null || workspaceId == null || workspaceId.isBlank()) {
            return false;
        }
        try {
            Optional<WorkspaceDocument> wsOpt = workspaceRepository.findByWorkspaceId(workspaceId.trim());
            if (wsOpt.isEmpty()) {
                return false;
            }
            String ownerId = wsOpt.get().getOwnerId();
            return ownerId != null && userId.equals(ownerId);
        } catch (Exception e) {
            log.debug("isUserOwnerOfWorkspace failed userId={} workspaceId={}: {}", userId, workspaceId, e.getMessage());
            return false;
        }
    }

    /**
     * True if the user owns the workspace associated with this project (direct or parent project doc).
     */
    public boolean isUserOwnerOfProject(String userId, String projectId) {
        if (userId == null) {
            return false;
        }
        try {
            return resolveWorkspaceIdForProject(projectId)
                    .filter(wsId -> isUserOwnerOfWorkspace(userId, wsId))
                    .isPresent();
        } catch (Exception e) {
            log.debug("isUserOwnerOfProject failed userId={} projectId={}: {}", userId, projectId, e.getMessage());
            return false;
        }
    }

    /**
     * Extract workspaceId from request URI by scanning path segments {@code proj-*}
     * (same semantics as legacy path walk: first segment that yields a workspace wins).
     */
    public Optional<String> resolveWorkspaceIdFromRequestPath(String uri) {
        if (uri == null || uri.isBlank()) {
            return Optional.empty();
        }
        try {
            String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
            for (String segment : decoded.split("/")) {
                if (segment == null || segment.isEmpty()) {
                    continue;
                }
                if (!segment.startsWith("proj-")) {
                    continue;
                }
                Optional<String> ws = resolveWorkspaceIdForProject(segment);
                if (ws.isPresent()) {
                    return ws;
                }
            }
        } catch (IllegalArgumentException e) {
            // Malformed percent-encoding in URI — legacy path logged and returned null
            log.debug("resolveWorkspaceIdFromRequestPath decode failed uri={}: {}", uri, e.getMessage());
        } catch (Exception e) {
            log.debug("resolveWorkspaceIdFromRequestPath failed uri={}: {}", uri, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns true if the user's role in the given project is VIEWER (read-only).
     * Uses the parent project ID (the proj-xxx segment before any "--" suffix).
     * Fail-open on lookup errors so editor stays accessible if auth DB is temporarily unreachable.
     */
    public boolean isViewerInProject(String userId, String projectId) {
        if (userId == null || projectId == null || projectId.isBlank()) return false;
        String parentProjectId = projectId.contains("--")
                ? projectId.substring(0, projectId.indexOf("--"))
                : projectId;
        try {
            Query q = new Query(Criteria.where("projectId").is(parentProjectId)
                    .and("members").elemMatch(
                            Criteria.where("userId").is(userId).and("role").regex("^VIEWER$", "i")));
            return mongoTemplate.exists(q, "projects");
        } catch (Exception e) {
            log.debug("isViewerInProject failed userId={} projectId={}: {}", userId, projectId, e.getMessage());
            return false;
        }
    }

    /**
     * Returns true if the user's project role is DRAFT_EDITOR — can edit their personal
     * draft copy and raise a pull request, but cannot write directly to the public ontology.
     */
    public boolean isDraftEditorInProject(String userId, String projectId) {
        if (userId == null || projectId == null || projectId.isBlank()) return false;
        String parentProjectId = projectId.contains("--")
                ? projectId.substring(0, projectId.indexOf("--"))
                : projectId;
        try {
            Query q = new Query(Criteria.where("projectId").is(parentProjectId)
                    .and("members").elemMatch(
                            Criteria.where("userId").is(userId).and("role").regex("^DRAFT_EDITOR$", "i")));
            return mongoTemplate.exists(q, "projects");
        } catch (Exception e) {
            log.debug("isDraftEditorInProject failed userId={} projectId={}: {}", userId, projectId, e.getMessage());
            return false;
        }
    }

    /**
     * Returns true if the user can publish (write) to the main ontology graph — i.e. they are
     * OWNER, ADMIN, or EDITOR in the project. Used to gate PR approval.
     */
    public boolean canPublishToProject(String userId, String projectId) {
        if (userId == null || projectId == null || projectId.isBlank()) return false;
        if (isUserOwnerOfProject(userId, projectId)) return true;
        String parentProjectId = projectId.contains("--")
                ? projectId.substring(0, projectId.indexOf("--"))
                : projectId;
        try {
            Query q = new Query(Criteria.where("projectId").is(parentProjectId)
                    .and("members").elemMatch(
                            Criteria.where("userId").is(userId)
                                    .and("role").regex("^(ADMIN|EDITOR)$", "i")));
            return mongoTemplate.exists(q, "projects");
        } catch (Exception e) {
            log.debug("canPublishToProject failed userId={} projectId={}: {}", userId, projectId, e.getMessage());
            return false;
        }
    }

    /**
     * Extract the first proj-* segment from the request path (used to resolve project role).
     */
    public Optional<String> resolveProjectIdFromRequestPath(String uri) {
        if (uri == null || uri.isBlank()) return Optional.empty();
        try {
            String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
            for (String segment : decoded.split("/")) {
                if (segment != null && segment.startsWith("proj-")) return Optional.of(segment);
            }
        } catch (Exception e) {
            log.debug("resolveProjectIdFromRequestPath failed uri={}: {}", uri, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * workspaceId query param (if present) takes precedence over path resolution — same as legacy interceptor.
     */
    public boolean isFreePlanUserOwner(String userId, String path, String workspaceIdQueryParam) {
        if (userId == null) {
            return false;
        }
        String wsParam = workspaceIdQueryParam != null ? workspaceIdQueryParam.trim() : null;
        if (wsParam != null && !wsParam.isBlank()) {
            return isUserOwnerOfWorkspace(userId, wsParam);
        }
        try {
            return resolveWorkspaceIdFromRequestPath(path)
                    .filter(wsId -> isUserOwnerOfWorkspace(userId, wsId))
                    .isPresent();
        } catch (Exception e) {
            log.debug("isFreePlanUserOwner failed userId={} path={}: {}", userId, path, e.getMessage());
            return false;
        }
    }
}
