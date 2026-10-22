package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.config.JwtClaimUtils;
import self.research.ontology.owlEditor.service.ImportReferenceUpdater;
import self.research.ontology.owlEditor.service.RequestActor;
import self.research.ontology.owlEditor.service.OntologyMetadataService;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ontology/metadata")
@CrossOrigin
public class OntologyIriController {

    private final OntologyMetadataService metadataService;
    private final ImportReferenceUpdater importReferenceUpdater;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private self.research.ontology.owlEditor.service.OperationGroups operationGroups;

    public OntologyIriController(OntologyMetadataService metadataService, ImportReferenceUpdater importReferenceUpdater) {
        this.metadataService = metadataService;
        this.importReferenceUpdater = importReferenceUpdater;
    }

    @PutMapping("/{projectId}/iri")
    public ResponseEntity<?> updateOntologyIRIs(@PathVariable String projectId, @RequestBody Map<String, String> request,
                                                HttpServletRequest httpRequest) {
        try {
            String ontologyIri = request.get("ontologyIri");
            String versionIri = request.get("versionIri");
            boolean draft = RequestActor.draftRequested(httpRequest, request.get("draft"));
            String userId = RequestActor.userIdOr(httpRequest, request.get("userId"));

            String username = request.get("username");
            String oldIri = draft ? null : metadataService.currentOntologyIri(projectId, false, userId);
            java.time.LocalDateTime since = java.time.LocalDateTime.now().minusSeconds(1);
            String groupId = java.util.UUID.randomUUID().toString();
            metadataService.updateOntologyIRIs(projectId, ontologyIri, versionIri, draft, userId, username);
            ImportReferenceUpdater.Result imports = draft ? ImportReferenceUpdater.Result.none()
                    : importReferenceUpdater.update(projectId, oldIri, ontologyIri, actor(httpRequest, userId, username), groupId);
            if (!draft && !imports.updated().isEmpty() && operationGroups != null) {
                operationGroups.tag(projectId, userId, since, groupId);
            }
            return ResponseEntity.ok(Map.of("success", true, "updatedImports", imports.updated(),
                    "skippedImports", imports.skipped()));
        } catch (Exception e) {
            log.error("Error updating ontology IRIs", e);
            return ResponseEntity.ok(Map.of("success", false, "error", String.valueOf(e.getMessage())));
        }
    }

    private static ImportReferenceUpdater.Actor actor(HttpServletRequest request, String userIdParam, String usernameParam) {
        String header = request.getHeader("Authorization");
        String[] claims = JwtClaimUtils.extractPlanAndUserId(header);
        String email = JwtClaimUtils.extractEmail(header);
        return new ImportReferenceUpdater.Actor(claims != null ? claims[1] : null, email != null ? email : userIdParam,
                usernameParam != null ? usernameParam : email);
    }
}
