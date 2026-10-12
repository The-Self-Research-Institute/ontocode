package self.research.ontology.owlEditor.service;

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
        if (projectId == null || projectId.isBlank() || email == null || email.isBlank()) {
            return false;
        }
        Optional<ProjectDocument> direct = projectRepository.findById(projectId);
        if (direct.isPresent() && direct.get().isAccessibleBy(email)) {
            return true;
        }
        int compositeSep = projectId.indexOf(COMPOSITE_PROJECT_SEPARATOR);
        String parentProjectId = compositeSep > 0 ? projectId.substring(0, compositeSep) : projectId;
        Query query = new Query(Criteria.where("projectId").is(parentProjectId).orOperator(
                Criteria.where("ownerEmail").is(email),
                Criteria.where("members").elemMatch(Criteria.where("email").is(email))));
        return mongoTemplate.exists(query, ProjectDocument.class, "projects");
    }
}
