package self.research.ontology.auth.service;

import org.springframework.stereotype.Component;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.util.CurrentUserEmail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class WorkspaceDtoMapper {

    private final WorkspaceBillingStatusResolver billingStatusResolver;

    public WorkspaceDtoMapper(WorkspaceBillingStatusResolver billingStatusResolver) {
        this.billingStatusResolver = billingStatusResolver;
    }

    public Map<String, Object> toDto(Workspace workspace) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", workspace.getId());
        dto.put("workspaceId", workspace.getWorkspaceId());
        dto.put("name", workspace.getName());
        dto.put("description", workspace.getDescription());
        dto.put("ownerId", workspace.getOwnerId());
        dto.put("memberCount", workspace.getMembers().size());

        String plan = workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "FREE";
        dto.put("subscriptionPlan", plan);
        dto.put("billingStatus", billingStatusResolver.resolve(workspace));
        dto.put("collaborationEnabled", workspace.getCollaborationEnabled());
        dto.put("collaborationLevel", WorkspacePlans.collaborationLevel(plan));
        dto.put("hasBasicCollaboration", WorkspacePlans.hasBasicCollaboration(plan));
        dto.put("hasAdvancedCollaboration", WorkspacePlans.hasAdvancedCollaboration(plan));

        dto.put("createdAt", workspace.getCreatedAt());
        dto.put("updatedAt", workspace.getUpdatedAt());

        boolean showInvitationTokens = workspace.canManageInvitationsByEmail(CurrentUserEmail.get());
        List<Map<String, Object>> members = workspace.getMembers().stream()
                .map(m -> {
                    Map<String, Object> memberDTO = new HashMap<>();
                    memberDTO.put("userId", m.getUserId());
                    memberDTO.put("username", m.getUsername());
                    memberDTO.put("email", m.getEmail());
                    memberDTO.put("role", m.getRole().toString());
                    memberDTO.put("joinedAt", m.getJoinedAt());
                    memberDTO.put("status", m.getStatus() != null ? m.getStatus().toString() : "ACTIVE");
                    if (showInvitationTokens) {
                        memberDTO.put("invitationToken", m.getInvitationToken());
                    }
                    return memberDTO;
                })
                .collect(Collectors.toList());
        dto.put("members", members);

        return dto;
    }
}
