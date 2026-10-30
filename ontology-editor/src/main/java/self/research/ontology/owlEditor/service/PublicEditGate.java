package self.research.ontology.owlEditor.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

@Component
public class PublicEditGate {

    private static final Logger log = LoggerFactory.getLogger(PublicEditGate.class);

    private final ProjectMetadataService metadataService;
    private final boolean desktopMode;

    public PublicEditGate(ProjectMetadataService metadataService,
                          @Value("${ontocode.desktop.mode:false}") boolean desktopMode) {
        this.metadataService = metadataService;
        this.desktopMode = desktopMode;
    }

    public static ResponseEntity<Map<String, Object>> refuse(PublicEditGate gate, String projectId, boolean draft) {
        return gate == null ? null : gate.refuse(projectId, draft);
    }

    public static ResponseEntity<Map<String, Object>> refuse(PublicEditGate gate, String projectId, boolean draft,
                                                             HttpServletRequest request) {
        return gate == null ? null : gate.refuse(projectId, draft, request);
    }

    public ResponseEntity<Map<String, Object>> refuse(String projectId, boolean draft) {
        return refuse(projectId, draft, currentRequest());
    }

    public ResponseEntity<Map<String, Object>> refuse(String projectId, boolean draft, HttpServletRequest request) {
        if (draft || desktopMode || projectId == null) {
            return null;
        }
        String userId = RequestActor.of(request, null, null).userId();
        if (!PublicEditGuard.blocks(metadataService, projectId, userId)) {
            return null;
        }
        log.info("[PUBLIC-EDIT] public editing is off - rejecting public write from user {} on project {}", userId, projectId);
        return PublicEditGuard.rejection();
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
