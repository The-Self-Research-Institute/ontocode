package self.research.ontology.owlEditor.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class MergeTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(MergeTargetResolver.class);

    private final ProjectMetadataService metadataService;
    private final ProjectAuthorization authorization;

    public MergeTargetResolver(ProjectMetadataService metadataService, ProjectAuthorization authorization) {
        this.metadataService = metadataService;
        this.authorization = authorization;
    }

    public String resolve(String defaultProjectId, String targetProjectId, String targetFileName) {
        if (targetProjectId != null && !targetProjectId.isBlank()) {
            if (metadataService.readStatus(targetProjectId).isPresent()) {
                return targetProjectId;
            }
            log.warn("[MERGE] Ignoring targetProjectId '{}' because no project status was found. "
                    + "Falling back to filename/default resolution.", targetProjectId);
        }
        if (targetFileName != null && !targetFileName.isBlank()) {
            return metadataService.getProjectIdByFilename(targetFileName)
                    .map(found -> {
                        log.info("[MERGE] Resolved target filename '{}' to project '{}'", targetFileName, found);
                        return found;
                    })
                    .orElse(defaultProjectId);
        }
        return defaultProjectId;
    }

    public Optional<AccessDenial> denialFor(HttpServletRequest request, String pathProjectId, String targetProjectId) {
        return targetProjectId.equals(pathProjectId)
                ? Optional.empty()
                : authorization.noAccessTo(request, targetProjectId);
    }
}
