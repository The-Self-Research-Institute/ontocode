package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.config.CallerIdentity;
import self.research.ontology.owlEditor.document.ProjectShare;
import self.research.ontology.owlEditor.service.AccessDenial;
import self.research.ontology.owlEditor.service.ProjectAuthorization;
import self.research.ontology.owlEditor.service.ProjectShareService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@RestController
@RequestMapping("/api/shares")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class ProjectShareController {

    private final ProjectShareService shareService;
    private final ProjectAuthorization authorization;

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createShare(@RequestBody Map<String, String> request,
                                                           HttpServletRequest httpRequest) {
        String projectId = request.get("projectId");
        return ownerOnly(httpRequest, projectId, () -> {
            String permission = request.getOrDefault("permission", "view");
            ProjectShare share = shareService.createShare(projectId, callerEmail(httpRequest, request.get("ownerEmail")),
                    permission);
            return Map.of("success", true, "share", share, "shareLink", share.getShareLink());
        }, "Failed to create share");
    }

    @PostMapping("/add-email")
    public ResponseEntity<Map<String, Object>> addEmailAccess(@RequestBody Map<String, String> request,
                                                              HttpServletRequest httpRequest) {
        String projectId = request.get("projectId");
        return ownerOnly(httpRequest, projectId, () -> Map.of("success", true, "share",
                shareService.addEmailAccess(projectId, request.get("email"), request.getOrDefault("permission", "view"))),
                "Failed to add email access");
    }

    @PostMapping("/remove-email")
    public ResponseEntity<Map<String, Object>> removeEmailAccess(@RequestBody Map<String, String> request,
                                                                 HttpServletRequest httpRequest) {
        String projectId = request.get("projectId");
        return ownerOnly(httpRequest, projectId, () -> Map.of("success", true, "share",
                shareService.removeEmailAccess(projectId, request.get("email"))), "Failed to remove email access");
    }

    @GetMapping("/project/{projectId}")
    public ResponseEntity<Map<String, Object>> getProjectShare(@PathVariable String projectId,
                                                               HttpServletRequest httpRequest) {
        return ownerOnly(httpRequest, projectId, () -> shareService.getShareByProjectId(projectId)
                .<Map<String, Object>>map(share -> Map.of("success", true, "share", share))
                .orElseGet(() -> Map.of("success", false, "message", "No share found")), "Failed to get share");
    }

    @GetMapping("/link/{shareLink}")
    public ResponseEntity<Map<String, Object>> getProjectByShareLink(@PathVariable String shareLink) {
        return shareService.getShareByLink(shareLink)
                .map(share -> ResponseEntity.ok(Map.of(
                    "success", true,
                    "share", (Object) share,
                    "projectId", share.getProjectId()
                )))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "error", "Share link not found or expired"
                )));
    }

    @GetMapping("/my-shares")
    public ResponseEntity<Map<String, Object>> getMyShares(@RequestParam(required = false) String email,
                                                           HttpServletRequest httpRequest) {
        return signedInOnly(httpRequest, () -> {
            List<ProjectShare> shares = shareService.getMyShares(callerEmail(httpRequest, email));
            return Map.of("success", true, "shares", shares);
        }, "Failed to get my shares");
    }

    @GetMapping("/shared-with-me")
    public ResponseEntity<Map<String, Object>> getSharedWithMe(@RequestParam(required = false) String email,
                                                               HttpServletRequest httpRequest) {
        return signedInOnly(httpRequest, () -> {
            List<ProjectShare> shares = shareService.getSharedWithMe(callerEmail(httpRequest, email));
            return Map.of("success", true, "shares", shares);
        }, "Failed to get shared with me");
    }

    @DeleteMapping("/project/{projectId}")
    public ResponseEntity<Map<String, Object>> deleteShare(@PathVariable String projectId,
                                                           HttpServletRequest httpRequest) {
        return ownerOnly(httpRequest, projectId, () -> {
            shareService.deleteShare(projectId);
            return Map.of("success", true, "message", "Share deleted");
        }, "Failed to delete share");
    }

    private ResponseEntity<Map<String, Object>> ownerOnly(HttpServletRequest request, String projectId,
                                                          Supplier<Map<String, Object>> action, String failure) {
        return guarded(authorization.notOwnerOf(request, projectId), action, failure);
    }

    private ResponseEntity<Map<String, Object>> signedInOnly(HttpServletRequest request,
                                                             Supplier<Map<String, Object>> action, String failure) {
        return guarded(authorization.unauthenticated(request), action, failure);
    }

    private ResponseEntity<Map<String, Object>> guarded(Optional<AccessDenial> denial,
                                                        Supplier<Map<String, Object>> action, String failure) {
        if (denial.isPresent()) {
            return denial.get().toResponse();
        }
        try {
            return ResponseEntity.ok(action.get());
        } catch (Exception e) {
            log.error(failure, e);
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", String.valueOf(e.getMessage())));
        }
    }

    private String callerEmail(HttpServletRequest request, String requestedEmail) {
        String email = CallerIdentity.of(request).email();
        return authorization.isDesktopMode() && (email == null || email.isBlank()) ? requestedEmail : email;
    }
}
