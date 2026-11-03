package self.research.ontology.owlEditor.service;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import self.research.ontology.owlEditor.document.ProjectDocument;

import java.util.List;
import java.util.Optional;

final class WorkspaceProjectRecord {

    private static final String COMPOSITE_PROJECT_SEPARATOR = "--";

    private WorkspaceProjectRecord() {
    }

    static String parentOf(String projectId) {
        int sep = projectId.indexOf(COMPOSITE_PROJECT_SEPARATOR);
        return sep > 0 ? projectId.substring(0, sep) : projectId;
    }

    static Optional<ProjectDocument> find(MongoTemplate mongo, String projectId) {
        if (mongo == null || projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        Query query = new Query(Criteria.where("projectId").is(parentOf(projectId))
                .and("members").exists(true).ne(List.of()));
        return Optional.ofNullable(mongo.findOne(query, ProjectDocument.class, "projects"));
    }

    static Optional<String> ownerEmail(ProjectDocument record) {
        if (record.getOwnerEmail() != null && !record.getOwnerEmail().isBlank()) {
            return Optional.of(record.getOwnerEmail());
        }
        if (record.getMembers() == null) {
            return Optional.empty();
        }
        return record.getMembers().stream()
                .filter(m -> m != null && "OWNER".equalsIgnoreCase(m.getRole()))
                .map(ProjectDocument.MemberRef::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .findFirst();
    }

    static boolean ownedBy(ProjectDocument record, String userId, String email) {
        if (userId != null && !userId.isBlank() && userId.equals(record.getOwnerId())) {
            return true;
        }
        return email != null && !email.isBlank()
                && ownerEmail(record).map(email::equalsIgnoreCase).orElse(false);
    }
}
