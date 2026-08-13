package self.research.ontology.owlEditor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Component
public class OwlApiFastPathSupport {

    @Autowired(required = false) @Nullable
    private OwlApiOntologyContext owlApiContext;

    @Autowired(required = false) @Nullable
    private DesktopOntologyLoader desktopOntologyLoader;

    @Autowired(required = false) @Nullable
    private SparqlDatasetService datasetService;

    @Value("${ontocode.desktop.owlapi-first:false}")
    private boolean owlApiFirst;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    public boolean owlApiReady(String projectId) {
        return owlApiContext != null && owlApiContext.hasOntology(projectId);
    }

    public boolean preferOwlApiPath(String projectId) {
        if (!owlApiFirst) {
            return false;
        }
        if (desktopMode) {
            return owlApiContext != null || desktopOntologyLoader != null;
        }
        if (desktopOntologyLoader == null) {
            return false;
        }
        String userId = SparqlQueryContext.getUserId();
        if (userId != null && !userId.isBlank() && datasetService != null
                && datasetService.hasActiveDraftOverlay(projectId, userId)) {
            return false;
        }
        return true;
    }

    public void ensureOwlApiWarming(String projectId) {
        if (desktopOntologyLoader != null) {
            desktopOntologyLoader.triggerLazyLoadIfNeeded(projectId);
        }
    }

    public ResponseEntity<?> owlApiWarmingListResponse() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("warming", true);
        body.put("owlapiReady", false);
        body.put("data", List.of());
        body.put("total", 0);
        body.put("message", "OWLAPI model is loading — retry shortly");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    public Optional<ResponseEntity<?>> owlApiOnlyOrWarming(String projectId, Supplier<ResponseEntity<?>> whenReady) {
        if (!preferOwlApiPath(projectId)) {
            return Optional.empty();
        }
        ensureOwlApiWarming(projectId);
        if (!owlApiReady(projectId)) {
            return Optional.of(owlApiWarmingListResponse());
        }
        try {
            return Optional.of(whenReady.get());
        } catch (Exception e) {
            if (e instanceof IllegalStateException
                    && e.getMessage() != null
                    && e.getMessage().contains("service unavailable")) {
                ensureOwlApiWarming(projectId);
                return Optional.of(owlApiWarmingListResponse());
            }
            if (desktopOntologyLoader != null && desktopOntologyLoader.isLoading(projectId)) {
                return Optional.of(owlApiWarmingListResponse());
            }
            if (!owlApiReady(projectId)) {
                return Optional.of(owlApiWarmingListResponse());
            }
            return Optional.of(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "error", "OWLAPI query failed: " + e.getMessage())));
        }
    }
}
