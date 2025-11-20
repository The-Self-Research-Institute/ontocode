package self.research.ontology.owlEditor.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.document.ProjectShare;
import self.research.ontology.owlEditor.service.ProjectShareService;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/shares")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProjectShareController {
    
    private final ProjectShareService shareService;
    
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createShare(@RequestBody Map<String, String> request) {
        try {
            String projectId = request.get("projectId");
            String ownerEmail = request.get("ownerEmail");
            String permission = request.getOrDefault("permission", "view");
            
            ProjectShare share = shareService.createShare(projectId, ownerEmail, permission);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "share", share,
                "shareLink", share.getShareLink()
            ));
        } catch (Exception e) {
            log.error("Failed to create share", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    @PostMapping("/add-email")
    public ResponseEntity<Map<String, Object>> addEmailAccess(@RequestBody Map<String, String> request) {
        try {
            String projectId = request.get("projectId");
            String email = request.get("email");
            String permission = request.getOrDefault("permission", "view");
            
            ProjectShare share = shareService.addEmailAccess(projectId, email, permission);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "share", share
            ));
        } catch (Exception e) {
            log.error("Failed to add email access", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    @PostMapping("/remove-email")
    public ResponseEntity<Map<String, Object>> removeEmailAccess(@RequestBody Map<String, String> request) {
        try {
            String projectId = request.get("projectId");
            String email = request.get("email");
            
            ProjectShare share = shareService.removeEmailAccess(projectId, email);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "share", share
            ));
        } catch (Exception e) {
            log.error("Failed to remove email access", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    @GetMapping("/project/{projectId}")
    public ResponseEntity<Map<String, Object>> getProjectShare(@PathVariable String projectId) {
        return shareService.getShareByProjectId(projectId)
                .map(share -> ResponseEntity.ok(Map.of(
                    "success", true,
                    "share", (Object) share
                )))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "No share found"
                )));
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
    public ResponseEntity<Map<String, Object>> getMyShares(@RequestParam String email) {
        try {
            List<ProjectShare> shares = shareService.getMyShares(email);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "shares", shares
            ));
        } catch (Exception e) {
            log.error("Failed to get my shares", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    @GetMapping("/shared-with-me")
    public ResponseEntity<Map<String, Object>> getSharedWithMe(@RequestParam String email) {
        try {
            List<ProjectShare> shares = shareService.getSharedWithMe(email);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "shares", shares
            ));
        } catch (Exception e) {
            log.error("Failed to get shared with me", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    @DeleteMapping("/project/{projectId}")
    public ResponseEntity<Map<String, Object>> deleteShare(@PathVariable String projectId) {
        try {
            shareService.deleteShare(projectId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Share deleted"
            ));
        } catch (Exception e) {
            log.error("Failed to delete share", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
}
