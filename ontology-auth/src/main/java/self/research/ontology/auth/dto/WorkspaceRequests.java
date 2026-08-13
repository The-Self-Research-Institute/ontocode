package self.research.ontology.auth.dto;

import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.Length;

import java.util.List;

public class WorkspaceRequests {

    public static class CreateWorkspaceRequest {

        @NotBlank(message = "Workspace name is required")
        @Length(min = 1, max = 255, message = "Workspace name must be between 1 and 255 characters")
        @Pattern(
            regexp = "^[^<>]*$",
            message = "Workspace name cannot contain < or > characters (XSS prevention)"
        )
        private String name;

        @Length(max = 1000, message = "Description cannot exceed 1000 characters")
        @Pattern(
            regexp = "^[^<>]*$",
            message = "Description cannot contain < or > characters (XSS prevention)"
        )
        private String description;

        @Pattern(
            regexp = "^(FREE|PRO|ENTERPRISE)$",
            message = "Invalid subscription plan. Must be FREE, PRO, or ENTERPRISE"
        )
        private String subscriptionPlan;

        public String getName() {
            return name != null ? name.trim() : null;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description != null ? description.trim() : null;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getSubscriptionPlan() {
            return subscriptionPlan != null ? subscriptionPlan.toUpperCase() : "FREE";
        }

        public void setSubscriptionPlan(String subscriptionPlan) {
            this.subscriptionPlan = subscriptionPlan;
        }
    }

    public static class UpdateWorkspaceRequest {

        @Length(min = 1, max = 255, message = "Workspace name must be between 1 and 255 characters")
        @Pattern(
            regexp = "^[^<>]*$",
            message = "Workspace name cannot contain < or > characters"
        )
        private String name;

        @Length(max = 1000, message = "Description cannot exceed 1000 characters")
        @Pattern(
            regexp = "^[^<>]*$",
            message = "Description cannot contain < or > characters"
        )
        private String description;

        public String getName() {
            return name != null ? name.trim() : null;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description != null ? description.trim() : null;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class AddMemberRequest {

        @NotBlank(message = "Email is required")
        @Email(
            regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$",
            message = "Invalid email format (RFC 5322)"
        )
        private String email;

        @NotBlank(message = "Role is required")
        @Pattern(
            regexp = "^(OWNER|ADMIN|MEMBER|VIEWER)$",
            message = "Invalid role. Must be OWNER, ADMIN, MEMBER, or VIEWER"
        )
        private String role;

        public String getEmail() {
            return email != null ? email.trim().toLowerCase() : null;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getRole() {
            return role != null ? role.toUpperCase() : null;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }

    public static class UpdateMemberRoleRequest {

        @NotBlank(message = "Role is required")
        @Pattern(
            regexp = "^(OWNER|ADMIN|MEMBER|VIEWER)$",
            message = "Invalid role. Must be OWNER, ADMIN, MEMBER, or VIEWER"
        )
        private String role;

        public String getRole() {
            return role != null ? role.toUpperCase() : null;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }

    public static class UpdateSubscriptionRequest {

        @NotBlank(message = "Subscription plan is required")
        @Pattern(
            regexp = "^(FREE|PRO|ENTERPRISE)$",
            message = "Invalid subscription plan. Must be FREE, PRO, or ENTERPRISE"
        )
        private String subscriptionPlan;

        public String getSubscriptionPlan() {
            return subscriptionPlan != null ? subscriptionPlan.toUpperCase() : null;
        }

        public void setSubscriptionPlan(String subscriptionPlan) {
            this.subscriptionPlan = subscriptionPlan;
        }
    }
}
