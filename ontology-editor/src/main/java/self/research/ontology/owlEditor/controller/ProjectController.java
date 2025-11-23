package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.document.ProjectShare;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.ProjectShareService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final ProjectShareService shareService;

    public ProjectController(ProjectRepository projectRepository, ProjectShareService shareService) {
        this.projectRepository = projectRepository;
        this.shareService = shareService;
    }

    @GetMapping
    public ResponseEntity<?> listProjects(@RequestParam(required = false) String userEmail) {
        try {
            List<ProjectDocument> allProjects = projectRepository.findAllByOrderByUpdatedAtDesc();
            
            // If no userEmail provided, return all projects (backward compatibility)
            if (userEmail == null || userEmail.isEmpty()) {
                List<Map<String, Object>> projects = allProjects.stream()
                    .map(this::mapProjectToInfo)
                    .collect(Collectors.toList());
                return ResponseEntity.ok(Map.of("success", true, "projects", projects));
            }
            
            // Separate into myFiles and sharedFiles
            List<Map<String, Object>> myFiles = allProjects.stream()
                .filter(doc -> userEmail.equals(doc.getOwnerEmail()))
                .map(doc -> {
                    Map<String, Object> info = mapProjectToInfo(doc);
                    // Add sharedWith info for files owned by user
                    shareService.getShareByProjectId(doc.getId()).ifPresent(share -> {
                        if (!share.getSharedWithEmails().isEmpty()) {
                            info.put("sharedWith", share.getSharedWithEmails());
                        }
                    });
                    return info;
                })
                .collect(Collectors.toList());
            
            // Get projects shared with me
            List<ProjectShare> sharedWithMe = shareService.getSharedWithMe(userEmail);
            List<String> sharedProjectIds = sharedWithMe.stream()
                .map(ProjectShare::getProjectId)
                .collect(Collectors.toList());
            
            List<Map<String, Object>> sharedFiles = allProjects.stream()
                .filter(doc -> sharedProjectIds.contains(doc.getId()))
                .map(doc -> {
                    Map<String, Object> info = mapProjectToInfo(doc);
                    // Add owner info for shared files
                    info.put("sharedBy", doc.getOwnerEmail());
                    return info;
                })
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "success", true, 
                "myFiles", myFiles,
                "sharedFiles", sharedFiles
            ));

        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Failed to list projects: " + e.getMessage()
            ));
        }
    }
    
    private Map<String, Object> mapProjectToInfo(ProjectDocument doc) {
        Map<String, Object> projectInfo = new HashMap<>();
        projectInfo.put("id", doc.getId());
        projectInfo.put("name", doc.getName());
        projectInfo.put("status", doc.getStatus());
        projectInfo.put("statusMessage", doc.getStatusMessage());
        projectInfo.put("updatedAt", doc.getUpdatedAt());
        projectInfo.put("filename", doc.getFilename());
        projectInfo.put("ownerEmail", doc.getOwnerEmail());
        
        if (doc.getMetadata() != null) {
            projectInfo.put("metadata", doc.getMetadata());
        }
        
        return projectInfo;
    }
}
