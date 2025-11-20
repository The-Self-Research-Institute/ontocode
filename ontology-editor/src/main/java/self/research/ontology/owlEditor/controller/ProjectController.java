package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin
public class ProjectController {

    private final ProjectRepository projectRepository;

    public ProjectController(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @GetMapping
    public ResponseEntity<?> listProjects() {
        try {
            List<ProjectDocument> projectDocs = projectRepository.findAllByOrderByUpdatedAtDesc();
            
            List<Map<String, Object>> projects = projectDocs.stream()
                .map(doc -> {
                    Map<String, Object> projectInfo = new HashMap<>();
                    projectInfo.put("id", doc.getId());
                    projectInfo.put("name", doc.getName());
                    projectInfo.put("status", doc.getStatus());
                    projectInfo.put("statusMessage", doc.getStatusMessage());
                    projectInfo.put("updatedAt", doc.getUpdatedAt());
                    projectInfo.put("filename", doc.getFilename());
                    
                    if (doc.getMetadata() != null) {
                        projectInfo.put("metadata", doc.getMetadata());
                    }
                    
                    return projectInfo;
                })
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of("success", true, "projects", projects));

        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Failed to list projects: " + e.getMessage()
            ));
        }
    }
}
