package self.research.ontology.auth.util;

import org.springframework.http.ResponseEntity;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

public record WorkspaceCaller(User user, Workspace workspace, ResponseEntity<?> failure) {

    public static WorkspaceCaller resolve(UserRepository users, Supplier<Optional<Workspace>> workspaceLookup) {
        return resolve(users, user -> true, workspaceLookup);
    }

    public static WorkspaceCaller resolve(UserRepository users, Predicate<User> hasAccess,
                                          Supplier<Optional<Workspace>> workspaceLookup) {
        Optional<User> userOpt = CurrentUserEmail.findUser(users);
        if (userOpt.isEmpty()) {
            return failed(WorkspaceErrors.userNotFound());
        }
        User user = userOpt.get();
        if (!hasAccess.test(user)) {
            return failed(WorkspaceErrors.noWorkspaceAccess());
        }
        Optional<Workspace> workspaceOpt = workspaceLookup.get();
        if (workspaceOpt.isEmpty()) {
            return failed(WorkspaceErrors.workspaceNotFound());
        }
        return new WorkspaceCaller(user, workspaceOpt.get(), null);
    }

    public WorkspaceCaller requireOwner(String forbiddenMessage) {
        if (failed() || workspace.getOwnerId().equals(user.getId())) {
            return this;
        }
        return failed(WorkspaceErrors.forbidden(forbiddenMessage));
    }

    public boolean failed() {
        return failure != null;
    }

    private static WorkspaceCaller failed(ResponseEntity<?> failure) {
        return new WorkspaceCaller(null, null, failure);
    }
}
