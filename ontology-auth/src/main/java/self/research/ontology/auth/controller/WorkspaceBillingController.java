package self.research.ontology.auth.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.auth.dto.WorkspaceRequests.UpdateSubscriptionRequest;
import self.research.ontology.auth.service.WorkspaceBillingService;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceBillingController {

    private final WorkspaceBillingService billingService;

    public WorkspaceBillingController(WorkspaceBillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/{workspaceId}/subscription")
    public ResponseEntity<?> getSubscription(@PathVariable String workspaceId) {
        return billingService.getSubscription(workspaceId);
    }

    @PatchMapping("/{workspaceId}/subscription")
    public ResponseEntity<?> updateSubscription(
            @PathVariable String workspaceId,
            @Valid @RequestBody UpdateSubscriptionRequest request) {
        return billingService.updateSubscription(workspaceId, request);
    }

    @PostMapping("/{workspaceId}/select")
    public ResponseEntity<?> selectWorkspace(@PathVariable String workspaceId) {
        return billingService.selectWorkspace(workspaceId);
    }
}
