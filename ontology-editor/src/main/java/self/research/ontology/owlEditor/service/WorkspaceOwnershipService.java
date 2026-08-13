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

    public boolean isUserAdminOfWorkspace(String userId, String workspaceId) {
        if (userId == null || workspaceId == null || workspaceId.isBlank()) {
            return false;
        }
        try {
            Query q = new Query(Criteria.where("workspaceId").is(workspaceId.trim())
                    .and("members").elemMatch(
                            Criteria.where("userId").is(userId).and("role").regex("^ADMIN$", "i")));
            return mongoTemplate.exists(q, "workspaces");
        } catch (Exception e) {
            log.debug("isUserAdminOfWorkspace failed userId={} workspaceId={}: {}", userId, workspaceId, e.getMessage());
            return false;
        }
    }

    public boolean isUserAdminOfProject(String userId, String projectId) {
        if (userId == null) {
            return false;
        }
        try {
            return resolveWorkspaceIdForProject(projectId)
                    .filter(wsId -> isUserAdminOfWorkspace(userId, wsId))
                    .isPresent();
        } catch (Exception e) {
            log.debug("isUserAdminOfProject failed userId={} projectId={}: {}", userId, projectId, e.getMessage());
            return false;
        }
    }

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

            log.debug("resolveWorkspaceIdFromRequestPath decode failed uri={}: {}", uri, e.getMessage());
        } catch (Exception e) {
            log.debug("resolveWorkspaceIdFromRequestPath failed uri={}: {}", uri, e.getMessage());
        }
        return Optional.empty();
    }

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

    public boolean canPublishToProject(String userId, String projectId) {
        if (userId == null || projectId == null || projectId.isBlank()) return false;
        if (isUserOwnerOfProject(userId, projectId)) return true;

        if (isUserAdminOfProject(userId, projectId)) return true;
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
