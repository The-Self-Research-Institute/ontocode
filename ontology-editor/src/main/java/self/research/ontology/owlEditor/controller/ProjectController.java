package self.research.ontology.owlEditor.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.ProjectMetadataService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin
public class ProjectController {

    private final ProjectMetadataService metadataService;
    private final Path projectsRoot;

    public ProjectController(ProjectMetadataService metadataService,
                             @Value("${ontocode.data.dir:./data}") String rootDir) {
        this.metadataService = metadataService;
        this.projectsRoot = Path.of(rootDir).toAbsolutePath().normalize().resolve("projects");
    }

    @GetMapping
    public ResponseEntity<?> listProjects() {
        try {
            List<Map<String, Object>> projects = new ArrayList<>();
            
            // Ensure projects directory exists
            if (!Files.exists(projectsRoot)) {
                return ResponseEntity.ok(Map.of("success", true, "projects", projects));
            }

            // Scan for project directories
            try (Stream<Path> dirs = Files.list(projectsRoot)) {
                dirs.filter(Files::isDirectory)
                    .forEach(projectDir -> {
                        String projectId = projectDir.getFileName().toString();
                        Map<String, Object> projectInfo = new HashMap<>();
                        projectInfo.put("id", projectId);
                        projectInfo.put("name", projectId);
                        
                        // Get status if available
                        metadataService.readStatus(projectId).ifPresent(status -> {
                            projectInfo.put("status", status.status());
                            projectInfo.put("statusMessage", status.statusMessage());
                            projectInfo.put("updatedAt", status.updatedAt());
                            projectInfo.put("filename", status.filename());
                        });
                        
                        // Get metadata if available
                        metadataService.readMeta(projectId).ifPresent(meta -> {
                            projectInfo.put("metadata", meta);
                        });
                        
                        projects.add(projectInfo);
                    });
            }

            // Sort by update time (most recent first)
            projects.sort((a, b) -> {
                String timeA = (String) a.getOrDefault("updatedAt", "");
                String timeB = (String) b.getOrDefault("updatedAt", "");
                return timeB.compareTo(timeA);
            });

            return ResponseEntity.ok(Map.of("success", true, "projects", projects));

        } catch (IOException e) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Failed to list projects: " + e.getMessage()
            ));
        }
    }
}
