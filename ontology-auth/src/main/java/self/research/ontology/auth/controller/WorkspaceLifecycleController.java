package self.research.ontology.auth.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.WorkspaceService;
import self.research.ontology.auth.util.CurrentUserEmail;
import self.research.ontology.auth.util.WorkspaceCaller;
import self.research.ontology.auth.util.WorkspaceErrors;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceLifecycleController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceLifecycleController.class);

    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;

    public WorkspaceLifecycleController(WorkspaceService workspaceService, UserRepository userRepository) {
        this.workspaceService = workspaceService;
        this.userRepository = userRepository;
    }

    @DeleteMapping("/{workspaceId}")
    public ResponseEntity<?> deleteWorkspace(@PathVariable String workspaceId) {
        try {
            WorkspaceCaller caller = WorkspaceCaller.resolve(userRepository,
                    () -> workspaceService.getWorkspace(workspaceId))
                    .requireOwner("Only workspace owner can delete the workspace");
            if (caller.failed()) {
                return caller.failure();
            }
            User user = caller.user();

            workspaceService.deleteWorkspace(workspaceId, user.getId());

            log.info("Workspace {} soft deleted by user {}", workspaceId, CurrentUserEmail.get());

            return ResponseEntity.ok(Map.of(
                "message", "Workspace deleted successfully"
            ));
        } catch (Exception e) {
            log.error("Error deleting workspace", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{workspaceId}/restore")
    public ResponseEntity<?> restoreWorkspace(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "true") boolean restoreProjects,
            @RequestParam(defaultValue = "true") boolean restoreFiles) {
        try {
            WorkspaceCaller caller = WorkspaceCaller.resolve(userRepository,
                    () -> workspaceService.getWorkspaceIncludingDeleted(workspaceId))
                    .requireOwner("Only workspace owner can restore the workspace");
            if (caller.failed()) {
                return caller.failure();
            }
            Workspace workspace = caller.workspace();
            String username = caller.user().getUsername();

            if (!Boolean.TRUE.equals(workspace.getIsDeleted())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace is not deleted"));
            }

            workspaceService.restoreWorkspace(workspaceId, restoreProjects, restoreFiles);

            log.info("Workspace {} restored by user {}", workspaceId, username);

            return ResponseEntity.ok(Map.of(
                "message", "Workspace restored successfully",
                "workspaceId", workspaceId,
                "projectsRestored", restoreProjects,
                "filesRestored", restoreFiles
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error restoring workspace: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/deleted")
    public ResponseEntity<?> getDeletedWorkspaces() {
        try {
            Optional<User> userOpt = CurrentUserEmail.findUser(userRepository);

            if (userOpt.isEmpty()) {
                return WorkspaceErrors.userNotFound();
            }

            User user = userOpt.get();
            List<Workspace> deletedWorkspaces = workspaceService.getDeletedUserWorkspaces(user.getId());

            return ResponseEntity.ok(deletedWorkspaces);
        } catch (Exception e) {
            log.error("Error getting deleted workspaces: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{workspaceId}/leave")
    public ResponseEntity<?> leaveWorkspace(@PathVariable String workspaceId) {
        try {
            WorkspaceCaller caller = WorkspaceCaller.resolve(userRepository,
                    () -> workspaceService.getWorkspace(workspaceId));
            if (caller.failed()) {
                return caller.failure();
            }
            User user = caller.user();
            Workspace workspace = caller.workspace();

            if (user.getId().equals(workspace.getOwnerId())) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Workspace owner cannot leave. Transfer ownership or delete the workspace."
                ));
            }
            if (!workspaceService.hasAccess(workspaceId, user.getId())) {
                return WorkspaceErrors.forbidden("You are not a member of this workspace");
            }

            workspaceService.leaveWorkspace(workspaceId, user.getId());
            log.info("User {} left workspace {}", user.getId(), workspaceId);
            return ResponseEntity.ok(Map.of("message", "You have left the workspace", "workspaceId", workspaceId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error leaving workspace", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
