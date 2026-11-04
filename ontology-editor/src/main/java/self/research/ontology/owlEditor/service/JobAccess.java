package self.research.ontology.owlEditor.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.Optional;

@Component
public class JobAccess {

    public record Caller(String userId, String email, boolean localDesktop) {
    }

    public record Verdict(int status, String callerId) {
        public boolean allowed() {
            return status == 200;
        }
    }

    private final RuleRunAccess identities;
    private final JobOwnerRegistry registry;

    @Autowired(required = false)
    private ProjectAccessService projectAccess;

    @Autowired
    public JobAccess(RuleRunAccess identities, JobOwnerRegistry registry) {
        this.identities = identities;
        this.registry = registry;
    }

    public JobAccess(RuleRunAccess identities, JobOwnerRegistry registry, ProjectAccessService projectAccess) {
        this(identities, registry);
        this.projectAccess = projectAccess;
    }

    public Optional<Caller> caller(HttpServletRequest request) {
        Optional<String> userId = identities.identity(request);
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        boolean desktop = identities.localDesktop(request);
        String email = JwtIdentityExtractor.extractEmail(request).orElse(null);
        return Optional.of(new Caller(userId.get(), email, desktop));
    }

    public boolean isRegistered(String jobId) {
        return registry.find(jobId).isPresent();
    }

    public Optional<String> callerId(HttpServletRequest request) {
        return caller(request).map(Caller::userId);
    }

    public void register(HttpServletRequest request, String jobId, String projectId) {
        registry.register(jobId, projectId, callerId(request).orElse(null));
    }

    public Verdict check(HttpServletRequest request, String routeProjectId, String jobId, String recordProjectId) {
        Optional<Caller> caller = caller(request);
        if (caller.isEmpty()) {
            return new Verdict(401, null);
        }
        return check(caller.get(), routeProjectId, jobId, recordProjectId);
    }

    public Verdict check(Caller caller, String routeProjectId, String jobId, String recordProjectId) {
        Optional<JobOwnerRegistry.Entry> entry = registry.find(jobId);
        String project = entry.map(JobOwnerRegistry.Entry::projectId).filter(p -> !p.isBlank()).orElse(recordProjectId);
        if (project == null || project.isBlank()) {
            project = routeProjectId;
        }
        if (project == null || project.isBlank()) {
            return notFound(caller);
        }
        if (routeProjectId != null && !routeProjectId.equals(project)) {
            return notFound(caller);
        }
        if (!hasProject(caller, project)) {
            return notFound(caller);
        }
        if (entry.isPresent() && !entry.get().owners().isEmpty() && !caller.localDesktop()
                && !entry.get().owners().contains(caller.userId())
                && !isProjectOwner(caller, project)) {
            return notFound(caller);
        }
        return new Verdict(200, caller.userId());
    }

    public Verdict checkProject(HttpServletRequest request, String projectId) {
        Optional<Caller> caller = caller(request);
        if (caller.isEmpty()) {
            return new Verdict(401, null);
        }
        return hasProject(caller.get(), projectId) ? new Verdict(200, caller.get().userId()) : notFound(caller.get());
    }

    public boolean hasProject(Caller caller, String projectId) {
        if (caller.localDesktop() || projectAccess == null) {
            return true;
        }
        return caller.email() != null && !caller.email().isBlank()
                && projectAccess.hasProjectAccess(projectId, caller.email());
    }

    private boolean isProjectOwner(Caller caller, String projectId) {
        return projectAccess != null && caller.email() != null && projectAccess.isProjectOwner(projectId, caller.email());
    }

    private static Verdict notFound(Caller caller) {
        return new Verdict(404, caller.userId());
    }
}
