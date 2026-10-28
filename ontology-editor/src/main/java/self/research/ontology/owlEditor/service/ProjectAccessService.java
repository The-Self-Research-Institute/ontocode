package self.research.ontology.owlEditor.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;

import java.util.Optional;

@Service
public class ProjectAccessService {

    private static final String COMPOSITE_PROJECT_SEPARATOR = "--";

    private final ProjectRepository projectRepository;
    private final MongoTemplate mongoTemplate;

    public ProjectAccessService(ProjectRepository projectRepository, MongoTemplate mongoTemplate) {
        this.projectRepository = projectRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public boolean projectExists(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        if (projectRepository.existsById(projectId)) {
            return true;
        }
        int compositeSep = projectId.indexOf(COMPOSITE_PROJECT_SEPARATOR);
        String parentProjectId = compositeSep > 0 ? projectId.substring(0, compositeSep) : projectId;
        return mongoTemplate.exists(new Query(Criteria.where("projectId").is(parentProjectId)), ProjectDocument.class,
                "projects");
    }

    public boolean hasProjectAccess(String projectId, String email) {
        return hasProjectAccess(projectId, null, email);
    }

    public boolean hasProjectAccess(String projectId, String userId, String email) {
        boolean noUserId = userId == null || userId.isBlank();
        if (projectId == null || projectId.isBlank() || ((email == null || email.isBlank()) && noUserId)) {
            return false;
        }
        Optional<ProjectDocument> direct = projectRepository.findById(projectId);
        if (direct.isPresent() && direct.get().isAccessibleBy(userId, email)) {
            return true;
        }
        int compositeSep = projectId.indexOf(COMPOSITE_PROJECT_SEPARATOR);
        String parentProjectId = compositeSep > 0 ? projectId.substring(0, compositeSep) : projectId;
        List<Criteria> anyOf = new ArrayList<>();
        if (!noUserId) {
            anyOf.add(Criteria.where("ownerId").is(userId));
            anyOf.add(Criteria.where("members").elemMatch(Criteria.where("userId").is(userId)));
        }
        if (email != null && !email.isBlank()) {
            java.util.regex.Pattern sameEmail = java.util.regex.Pattern.compile(
                    "^" + java.util.regex.Pattern.quote(email.trim()) + "$", java.util.regex.Pattern.CASE_INSENSITIVE);
            anyOf.add(Criteria.where("ownerEmail").regex(sameEmail));
            anyOf.add(Criteria.where("members").elemMatch(Criteria.where("email").regex(sameEmail)));
        }
        Query query = new Query(Criteria.where("projectId").is(parentProjectId)
                .orOperator(anyOf.toArray(new Criteria[0])));
        return mongoTemplate.exists(query, ProjectDocument.class, "projects");
    }

    public boolean isOwner(String projectId, String userId, String email) {
        boolean noUserId = userId == null || userId.isBlank();
        boolean noEmail = email == null || email.isBlank();
        if (projectId == null || projectId.isBlank() || (noUserId && noEmail)) {
            return false;
        }
        Optional<ProjectDocument> direct = projectRepository.findById(projectId);
        if (direct.isPresent() && ownedBy(direct.get(), userId, email)) {
            return true;
        }
        int compositeSep = projectId.indexOf(COMPOSITE_PROJECT_SEPARATOR);
        String parentProjectId = compositeSep > 0 ? projectId.substring(0, compositeSep) : projectId;
        List<Criteria> anyOf = new ArrayList<>();
        if (!noUserId) {
            anyOf.add(Criteria.where("ownerId").is(userId));
        }
        if (!noEmail) {
            anyOf.add(Criteria.where("ownerEmail").regex(java.util.regex.Pattern.compile(
                    "^" + java.util.regex.Pattern.quote(email.trim()) + "$", java.util.regex.Pattern.CASE_INSENSITIVE)));
        }
        Query query = new Query(Criteria.where("projectId").is(parentProjectId)
                .orOperator(anyOf.toArray(new Criteria[0])));
        return mongoTemplate.exists(query, ProjectDocument.class, "projects");
    }

    private static boolean ownedBy(ProjectDocument doc, String userId, String email) {
        if (userId != null && !userId.isBlank() && userId.equals(doc.getOwnerId())) {
            return true;
        }
        return email != null && !email.isBlank() && email.equalsIgnoreCase(doc.getOwnerEmail());
    }

    public boolean isProjectOwner(String projectId, String email) {
        return isOwner(projectId, null, email);
    }
}
