package self.research.ontology.auth.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.dto.WorkspaceRequests.*;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.SystemSettingsService;
import self.research.ontology.auth.service.WorkspaceDtoMapper;
import self.research.ontology.auth.service.WorkspacePlans;
import self.research.ontology.auth.service.WorkspaceService;
import self.research.ontology.auth.util.CurrentUserEmail;
import self.research.ontology.auth.util.WorkspaceCaller;
import self.research.ontology.auth.util.WorkspaceErrors;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;
    private final SystemSettingsService systemSettingsService;
    private final WorkspaceDtoMapper dtoMapper;

    public WorkspaceController(WorkspaceService workspaceService,
                              UserRepository userRepository,
                              SystemSettingsService systemSettingsService,
                              WorkspaceDtoMapper dtoMapper) {
        this.workspaceService = workspaceService;
        this.userRepository = userRepository;
        this.systemSettingsService = systemSettingsService;
        this.dtoMapper = dtoMapper;
    }

    @GetMapping
    public ResponseEntity<?> getUserWorkspaces() {
        try {
            Optional<User> userOpt = CurrentUserEmail.findUser(userRepository);

            if (userOpt.isEmpty()) {
                return WorkspaceErrors.userNotFound();
            }

            User user = userOpt.get();

            workspaceService.syncWorkspacesToOwnerPlan(user);

            List<Workspace> workspaces = workspaceService.getUserWorkspaces(user.getId());

            workspaces.stream()
                    .map(Workspace::getOwnerId)
                    .distinct()
                    .forEach(ownerId -> userRepository.findById(ownerId).ifPresent(workspaceService::syncWorkspacesToOwnerPlan));
            workspaces = workspaceService.getUserWorkspaces(user.getId());

            List<Map<String, Object>> workspaceDTOs = workspaces.stream()
                    .map(dtoMapper::toDto)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "workspaces", workspaceDTOs,
                "userId", user.getId()
            ));
        } catch (Exception e) {
            log.error("Error fetching workspaces", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/check")
    public ResponseEntity<?> checkWorkspaceExists(@RequestParam String name) {
        try {
            Optional<User> userOpt = CurrentUserEmail.findUser(userRepository);

            if (userOpt.isEmpty()) {
                return WorkspaceErrors.userNotFound();
            }

            User user = userOpt.get();
            List<Workspace> userWorkspaces = workspaceService.getUserWorkspaces(user.getId());

            Optional<Workspace> existingWorkspace = userWorkspaces.stream()
                    .filter(w -> w.getName().equalsIgnoreCase(name.trim()))
                    .findFirst();

            if (existingWorkspace.isPresent()) {
                return ResponseEntity.ok(Map.of(
                    "exists", true,
                    "name", name,
                    "existingWorkspace", Map.of(
                        "id", existingWorkspace.get().getId(),
                        "name", existingWorkspace.get().getName(),
                        "createdAt", existingWorkspace.get().getCreatedAt()
                    )
                ));
            }

            return ResponseEntity.ok(Map.of(
                "exists", false,
                "name", name
            ));
        } catch (Exception e) {
            log.error("Error checking workspace existence", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> createWorkspace(@Valid @RequestBody CreateWorkspaceRequest request) {
        try {
            Optional<User> userOpt = CurrentUserEmail.findUser(userRepository);

            if (userOpt.isEmpty()) {
                return WorkspaceErrors.userNotFound();
            }

            User user = userOpt.get();
            String username = user.getUsername();

            String rawAccountPlan = user.getSubscriptionPlanName() != null
                    ? user.getSubscriptionPlanName().toUpperCase() : "FREE";
            String accountStatus = user.getSubscriptionStatus();

            if (systemSettingsService.isEnterpriseBypass(user.getEmail())) {
                rawAccountPlan = "ENTERPRISE";
                accountStatus = "active";
            }

            String activeAccountPlan = ("active".equalsIgnoreCase(accountStatus)
                    || "trialing".equalsIgnoreCase(accountStatus))
                    ? rawAccountPlan : "FREE";

            List<Workspace> ownedWorkspaces = workspaceService.getOwnedWorkspaces(user.getId());
            int maxWorkspaces = WorkspacePlans.maxWorkspacesForPlan(activeAccountPlan);
            log.info("createWorkspace: user={} planName={} status={} activePlan={} ownedCount={} maxAllowed={}",
                    username, rawAccountPlan, accountStatus, activeAccountPlan, ownedWorkspaces.size(), maxWorkspaces);
            if (ownedWorkspaces.size() >= maxWorkspaces) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Workspace limit reached (" + maxWorkspaces + " workspaces on " + activeAccountPlan + " plan). Upgrade your account to create more.",
                    "currentCount", ownedWorkspaces.size(),
                    "maxAllowed", maxWorkspaces,
                    "accountPlan", activeAccountPlan
                ));
            }

            boolean nameExists = ownedWorkspaces.stream()
                .anyMatch(w -> w.getName().equalsIgnoreCase(request.getName()));
            if (nameExists) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "A workspace with this name already exists"
                ));
            }

            Workspace workspace = workspaceService.createWorkspace(
                user.getId(),
                request.getName(),
                request.getDescription()
            );
            workspace.setMaxWorkspaces(maxWorkspaces);
            workspace = workspaceService.updateWorkspace(workspace);

            return ResponseEntity.ok(Map.of(
                "message", "Workspace created successfully",
                "workspace", dtoMapper.toDto(workspace)
            ));
        } catch (IllegalArgumentException e) {
            log.error("Validation error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating workspace", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{workspaceId}")
    public ResponseEntity<?> getWorkspace(@PathVariable String workspaceId) {
        try {
            WorkspaceCaller caller = WorkspaceCaller.resolve(userRepository,
                    user -> workspaceService.hasAccess(workspaceId, user.getId()),
                    () -> workspaceService.getWorkspace(workspaceId));
            if (caller.failed()) {
                return caller.failure();
            }

            return ResponseEntity.ok(dtoMapper.toDto(caller.workspace()));
        } catch (Exception e) {
            log.error("Error fetching workspace", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{workspaceId}")
    public ResponseEntity<?> updateWorkspace(
            @PathVariable String workspaceId,
            @RequestBody Map<String, String> request) {
        try {
            WorkspaceCaller caller = WorkspaceCaller.resolve(userRepository,
                    () -> workspaceService.getWorkspace(workspaceId))
                    .requireOwner("Only workspace owner can update workspace settings");
            if (caller.failed()) {
                return caller.failure();
            }
            User user = caller.user();

            String name = request.get("name");
            if (name != null && !name.isBlank()) {
                String trimmedName = name.trim();
                boolean duplicateName = workspaceService.getOwnedWorkspaces(user.getId()).stream()
                    .anyMatch(w -> !w.getWorkspaceId().equals(workspaceId)
                        && w.getName().equalsIgnoreCase(trimmedName));
                if (duplicateName) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "A workspace with this name already exists"
                    ));
                }
            }

            Workspace updatedWorkspace = workspaceService.updateWorkspace(
                workspaceId,
                name,
                request.get("description")
            );

            return ResponseEntity.ok(Map.of(
                "message", "Workspace updated successfully",
                "workspace", dtoMapper.toDto(updatedWorkspace)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating workspace {}", workspaceId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
