package self.research.ontology.auth.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.util.List;

/**
 * Migration utility to create default workspaces for existing users
 * This will run automatically on application startup
 */
@Component
public class WorkspaceMigration implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMigration.class);

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;

    public WorkspaceMigration(UserRepository userRepository, WorkspaceRepository workspaceRepository) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public void run(String... args) {
        log.info("🔄 Running workspace migration...");
        
        try {
            List<User> allUsers = userRepository.findAll();
            int migrated = 0;
            
            for (User user : allUsers) {
                // Check if user already has a workspace
                List<Workspace> userWorkspaces = workspaceRepository.findByOwnerId(user.getId());
                
                if (userWorkspaces.isEmpty()) {
                    // Create a default workspace for this user
                    Workspace defaultWorkspace = new Workspace();
                    defaultWorkspace.setWorkspaceId(user.getUsername() + "-workspace");
                    defaultWorkspace.setOwnerId(user.getId());
                    defaultWorkspace.setName(user.getUsername() + "'s Workspace");
                    defaultWorkspace.setDescription("Default workspace");
                    
                    // Add user as owner
                    defaultWorkspace.addMember(
                        user.getId(), 
                        user.getUsername(), 
                        user.getEmail(), 
                        Workspace.WorkspaceRole.OWNER
                    );
                    
                    // Set default plan
                    defaultWorkspace.setSubscriptionPlan("FREE");
                    defaultWorkspace.setMaxWorkspaces(3);
                    defaultWorkspace.setMaxMembers(3);
                    defaultWorkspace.setCollaborationEnabled(false);
                    
                    workspaceRepository.save(defaultWorkspace);
                    migrated++;
                    
                    log.info("✓ Created default workspace for user: {}", user.getUsername());
                }
            }
            
            if (migrated > 0) {
                log.info("✅ Workspace migration complete: {} default workspaces created", migrated);
            } else {
                log.info("✅ Workspace migration complete: All users already have workspaces");
            }
            
        } catch (Exception e) {
            log.error("❌ Error during workspace migration:", e);
        }
    }
}
