package self.research.ontology.owlEditor.controller;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import self.research.ontology.owlEditor.dto.OntologyDtos.*;
import self.research.ontology.owlEditor.service.OntologySparqlService;
import self.research.ontology.owlEditor.service.OwlParsingService;

import java.io.InputStream;
import java.util.*;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(origins = "*")
public class ProjectLoadController {
    
    private static final Logger log = LoggerFactory.getLogger(ProjectLoadController.class);
    
    @Autowired
    private MongoTemplate mongo;
    
    @Autowired
    private GridFsTemplate gridfs;
    
    @Autowired
    private OwlParsingService parser;
    
    @Autowired
    private OntologySparqlService sparql;

    // ========== UPLOAD & STATUS ==========

    @PostMapping("/upload/{projectId}")
    public ResponseEntity<Map<String,Object>> upload(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file) {
        
        log.info("Received upload request for project: {}, filename: {}", 
                projectId, file.getOriginalFilename());
        
        try {
            // Save file to GridFS
            ObjectId fileId = gridfs.store(
                file.getInputStream(),
                file.getOriginalFilename(),
                file.getContentType()
            );
            
            log.info("Stored file in GridFS with ID: {}", fileId);
            
            // Create/update project document
            Map<String, Object> projectDoc = new HashMap<>();
            projectDoc.put("_id", projectId);
            projectDoc.put("filename", file.getOriginalFilename());
            projectDoc.put("gridfsFileId", fileId.toString());
            projectDoc.put("status", "UPLOADED");
            projectDoc.put("statusMessage", "File uploaded, starting processing...");
            projectDoc.put("createdAt", new Date());
            projectDoc.put("updatedAt", new Date());
            
            mongo.save(projectDoc, "projects");
            
            // Start async parsing
            parser.parseAndIndex(projectId, fileId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("projectId", projectId);
            response.put("message", "File uploaded successfully, processing started");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to upload file for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "File upload failed: " + e.getMessage()));
        }
    }

    @GetMapping("/status/{projectId}")
    public ResponseEntity<Map<String,Object>> status(@PathVariable String projectId) {
        Map<?, ?> project = mongo.findOne(
            new Query(Criteria.where("_id").is(projectId)), 
            Map.class, 
            "projects"
        );
        
        if (project == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "error", "Project not found"));
        }
        
        Map<String,Object> statusData = new HashMap<>();
        statusData.put("status", project.getOrDefault("status", "UNKNOWN"));
        statusData.put("statusMessage", project.getOrDefault("statusMessage", ""));
        statusData.put("updatedAt", project.getOrDefault("updatedAt", new Date()));
        
        return ResponseEntity.ok(Map.of("success", true, "data", statusData));
    }

    @GetMapping("/export/{projectId}")
    public ResponseEntity<byte[]> export(@PathVariable String projectId) {
        log.info("Export request for project: {}", projectId);
        
        try {
            Map<?, ?> projectData = mongo.findOne(
                new Query(Criteria.where("_id").is(projectId)), 
                Map.class, 
                "projects"
            );
            
            if (projectData == null || projectData.get("gridfsFileId") == null) {
                log.warn("Project or file not found: {}", projectId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(("Project not found: " + projectId).getBytes());
            }
            
            String fileIdStr = (String) projectData.get("gridfsFileId");
            String filename = (String) projectData.getOrDefault("filename", "ontology.owl");
            
            GridFSFile file = gridfs.findOne(
                new Query(Criteria.where("_id").is(new ObjectId(fileIdStr)))
            );
            
            if (file == null) {
                log.warn("File not found in GridFS: {}", fileIdStr);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(("File not found: " + fileIdStr).getBytes());
            }
            
            GridFsResource resource = gridfs.getResource(file);
            byte[] data;
            try (InputStream inputStream = resource.getInputStream()) {
                data = inputStream.readAllBytes();
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(data.length);
            
            log.info("Successfully exported {} bytes for file: {}", data.length, filename);
            return new ResponseEntity<>(data, headers, HttpStatus.OK);
            
        } catch (Exception e) {
            log.error("Error exporting ontology: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Export failed: " + e.getMessage()).getBytes());
        }
    }

    // ========== METADATA ==========

    @GetMapping("/metadata/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> metadata(@PathVariable String projectId) {
        return sparql.getMetadata(projectId)
                .map(m -> ResponseEntity.ok(Map.of("success", true, "data", m)))
                .defaultIfEmpty(ResponseEntity.ok(Map.of("success", false, "error", "Metadata not found")));
    }

    // ========== CLASSES ==========

    @GetMapping("/classes/top-level/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> topLevel(@PathVariable String projectId) {
        return sparql.getTopLevelClasses(projectId)
                .map(classes -> ResponseEntity.ok(Map.of("success", true, "classes", classes)))
                .defaultIfEmpty(ResponseEntity.ok(Map.of("success", true, "classes", Collections.emptyList())));
    }

    @GetMapping("/classes/children/{projectId}")
    public Mono<ResponseEntity<List<TreeNode>>> children(
            @PathVariable String projectId,
            @RequestParam String parentIri) {
        return sparql.getClassChildren(projectId, parentIri)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.ok(Collections.emptyList()));
    }

    @GetMapping("/all-classes")
    public Mono<ResponseEntity<Map<String,Object>>> allClasses(@RequestParam String projectId) {
        return sparql.getAllClassesWithParent(projectId)
                .map(classes -> ResponseEntity.ok(Map.of("success", true, "classes", classes)))
                .defaultIfEmpty(ResponseEntity.ok(Map.of("success", true, "classes", Collections.emptyList())));
    }

    // ========== PROPERTIES ==========

    @GetMapping("/properties/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> properties(@PathVariable String projectId) {
        return sparql.getProperties(projectId)
                .map(properties -> ResponseEntity.ok(Map.of("success", true, "data", properties)))
                .defaultIfEmpty(ResponseEntity.ok(Map.of("success", true, "data", Collections.emptyList())));
    }

    // ========== INDIVIDUALS ==========

    @GetMapping("/individuals/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> individuals(@PathVariable String projectId) {
        return sparql.getIndividuals(projectId)
                .map(individuals -> ResponseEntity.ok(Map.of("success", true, "data", individuals)))
                .defaultIfEmpty(ResponseEntity.ok(Map.of("success", true, "data", Collections.emptyList())));
    }

    // ========== ANNOTATION PROPERTIES ==========

    @GetMapping("/annotation-properties/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> annProps(@PathVariable String projectId) {
        return sparql.getAnnotationProperties(projectId)
                .map(props -> ResponseEntity.ok(Map.of("success", true, "data", props)))
                .defaultIfEmpty(ResponseEntity.ok(Map.of("success", true, "data", Collections.emptyList())));
    }

    // ========== DATATYPES ==========

    @GetMapping("/datatypes/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> datatypes(@PathVariable String projectId) {
        return sparql.getDatatypes(projectId)
                .map(datatypes -> ResponseEntity.ok(Map.of("success", true, "data", datatypes)))
                .defaultIfEmpty(ResponseEntity.ok(Map.of("success", true, "data", Collections.emptyList())));
    }

    // ========== SEARCH ==========

    @GetMapping("/search/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> search(
            @PathVariable String projectId,
            @RequestParam String q,
            @RequestParam(required = false) String type) {
        return sparql.searchEntities(projectId, q, type != null ? type : "")
                .map(results -> ResponseEntity.ok(Map.of("success", true, "data", results)))
                .defaultIfEmpty(ResponseEntity.ok(Map.of("success", true, "data", Collections.emptyList())));
    }

    // ========== NAMESPACES ==========

    @GetMapping("/namespaces/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> namespaces(@PathVariable String projectId) {
        return sparql.getNamespaces(projectId)
                .map(ns -> ResponseEntity.ok(Map.of("success", true, "data", ns)))
                .defaultIfEmpty(ResponseEntity.ok(Map.of("success", true, "data", Collections.emptyList())));
    }

    // ========== STATISTICS ==========

    @GetMapping("/statistics/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> statistics(@PathVariable String projectId) {
        return sparql.getStatistics(projectId)
                .map(stats -> ResponseEntity.ok(Map.of("success", true, "data", stats)))
                .defaultIfEmpty(ResponseEntity.ok(Map.of("success", true, "data", Map.of())));
    }
}