package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.owlEditor.document.OntologyDocument;
import self.research.ontology.owlEditor.dto.*;
import self.research.ontology.owlEditor.service.OntologyIndexService;
import self.research.ontology.owlEditor.service.OwlParsingService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import com.mongodb.client.gridfs.model.GridFSFile;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.bson.types.ObjectId;
import java.io.InputStream;

@CrossOrigin(origins = "*") 
@RestController
@RequestMapping("/api/ontology")
public class ProjectLoadController {

    private static final Logger logger = LoggerFactory.getLogger(ProjectLoadController.class);

    @Autowired
    private OntologyIndexService ontologyIndexService;

    @Autowired
    private OwlParsingService owlParsingService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private GridFsTemplate gridFsTemplate;

    @PostMapping("/upload/{projectId}")
    public ResponseEntity<Map<String, Object>> uploadOntology(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file) {

        logger.info("Receiving ontology file upload for project: {}", projectId);

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(createErrorResponse("File is empty"));
            }

            String filename = file.getOriginalFilename();
            String contentType = file.getContentType();
            logger.info("File received: {} (size: {} bytes) for project: {}", filename, file.getSize(), projectId);

            ObjectId fileId;
            try (InputStream gridfsStream = file.getInputStream()) {
                fileId = gridFsTemplate.store(gridfsStream, filename, contentType);
            }
            logger.info("Stored file in GridFS with ID: {} for project: {}", fileId, projectId);

            createOrUpdateProject(projectId, filename, fileId.toString(), "PROCESSING", "Starting to process ontology...");

            try (InputStream parseStream = file.getInputStream()) {
                owlParsingService.parseAndIndexFromStream(projectId, parseStream, filename);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "File uploaded successfully and processing started");
            response.put("projectId", projectId);
            response.put("filename", filename);
            response.put("fileId", fileId.toString());
            response.put("status", "PROCESSING");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error uploading ontology file for project {}: {}", projectId, e.getMessage(), e);
            createOrUpdateProject(projectId, (file != null ? file.getOriginalFilename() : "unknown"), null, "ERROR", "Failed to upload file: " + e.getMessage());
            return ResponseEntity.status(500).body(createErrorResponse("Failed to upload file: " + e.getMessage()));
        }
    }

    @GetMapping("/status/{projectId}")
    public ResponseEntity<Map<String, Object>> getProcessingStatus(@PathVariable String projectId) {
        logger.info("Checking processing status for project: {}", projectId);

        try {
            Query query = new Query(Criteria.where("_id").is(projectId));
            Map projectData = mongoTemplate.findOne(query, Map.class, "projects");

            Map<String, Object> response = new HashMap<>();
            Map<String, Object> data = new HashMap<>();

            if (projectData != null) {
                data.put("status", projectData.get("status"));
                data.put("statusMessage", projectData.get("statusMessage"));
                data.put("filename", projectData.get("filename"));
                data.put("lastUpdated", projectData.get("lastUpdated"));
            } else {
                OntologyDocument doc = ontologyIndexService.getOntologyMetadata(projectId);
                if (doc != null) {
                    data.put("status", "COMPLETED");
                    data.put("statusMessage", "Processing complete (project status not found, but data exists).");
                    data.put("filename", doc.getMetadata() != null ? doc.getMetadata().getFilename() : "Unknown");
                    data.put("lastUpdated", doc.getUpdatedAt());
                } else {
                    data.put("status", "NOT_FOUND");
                    data.put("statusMessage", "Project not found");
                }
            }

            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error checking status for project: {}", projectId, e);
            return ResponseEntity.status(500).body(createErrorResponse("Failed to check status"));
        }
    }

    @GetMapping("/export/{projectId}")
    public ResponseEntity<byte[]> exportOntology(@PathVariable String projectId) {
        logger.info("Handling export request for project: {}", projectId);
        try {
            Query projectQuery = new Query(Criteria.where("_id").is(projectId));
            Map projectData = mongoTemplate.findOne(projectQuery, Map.class, "projects");

            if (projectData == null || projectData.get("filename") == null) {
                logger.warn("Project or filename not found for export: {}", projectId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(("Project not found or has no associated file: " + projectId).getBytes());
            }
            
            String filename = (String) projectData.get("filename");
            logger.info("Found filename: {} for project: {}", filename, projectId);

            GridFSFile file = gridFsTemplate.findOne(new Query(Criteria.where("filename").is(filename)));
            
            if (file == null) {
                logger.warn("File not found in GridFS: {}", filename);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(("File not found in GridFS: " + filename).getBytes());
            }

            GridFsResource resource = gridFsTemplate.getResource(file);
            byte[] data;
            try (InputStream inputStream = resource.getInputStream()) {
                data = inputStream.readAllBytes();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(data.length);

            logger.info("Successfully exporting {} bytes for file: {}", data.length, filename);
            
            return new ResponseEntity<>(data, headers, HttpStatus.OK);

        } catch (Exception e) {
            logger.error("Error exporting ontology for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Failed to export ontology: " + e.getMessage()).getBytes());
        }
    }

    @GetMapping("/metadata/{projectId}")
    public ResponseEntity<Map<String, Object>> getMetadata(@PathVariable String projectId) {
        logger.info("Fetching metadata for project: {}", projectId);
        try {
            OntologyDocument ontologyDoc = ontologyIndexService.getOntologyMetadata(projectId);
            if (ontologyDoc == null) {
                return ResponseEntity.status(404).body(createErrorResponse("Metadata not found"));
            }
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", ontologyDoc);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching metadata for project: {}", projectId, e);
            return ResponseEntity.status(500).body(createErrorResponse("Failed to fetch metadata"));
        }
    }

    @GetMapping("/classes/tree/{projectId}")
    public ResponseEntity<List<TreeNode>> getClassHierarchy(@PathVariable String projectId) {
        logger.info("Fetching class hierarchy for project: {}", projectId);
        try {
            List<TreeNode> classHierarchy = ontologyIndexService.getClassHierarchy(projectId);
            logger.info("Returning {} root nodes for project: {}", classHierarchy.size(), projectId);
            return ResponseEntity.ok(classHierarchy);
        } catch (Exception e) {
            logger.error("Error fetching class hierarchy for project: {}", projectId, e);
            return ResponseEntity.ok(new ArrayList<>());
        }
    }

    @GetMapping("/classes/top-level/{projectId}")
    public ResponseEntity<Map<String, Object>> getTopLevelClasses(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String search) {

        logger.info("Fetching top-level classes: page={}, size={}, search={}", page, size, search);

        try {
            Map<String, Object> result = ontologyIndexService.getTopLevelClassesPaginated(projectId, page, size, search);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error fetching top-level classes for project: {}", projectId, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("classes", new ArrayList<>());
            errorResponse.put("total", 0);
            errorResponse.put("page", page);
            errorResponse.put("size", size);
            errorResponse.put("hasMore", false);
            return ResponseEntity.ok(errorResponse);
        }
    }

    @GetMapping("/classes/children/{projectId}")
    public ResponseEntity<List<TreeNode>> getClassChildren(
            @PathVariable String projectId,
            @RequestParam String parentIri) {

        logger.info("Fetching children for parent: {} in project: {}", parentIri, projectId);

        try {
            List<TreeNode> children = ontologyIndexService.getClassChildren(projectId, parentIri);
            logger.info("Returning {} children for parent: {}", children.size(), parentIri);
            return ResponseEntity.ok(children);
        } catch (Exception e) {
            logger.error("Error fetching children for parent: {}", parentIri, e);
            return ResponseEntity.ok(new ArrayList<>());
        }
    }

    @GetMapping("/classes/search/{projectId}")
    public ResponseEntity<List<TreeNode>> searchClasses(
            @PathVariable String projectId,
            @RequestParam String query) {
        logger.info("Searching classes in project: {} with query: {}", projectId, query);
        try {
            List<TreeNode> results = ontologyIndexService.searchClasses2(projectId, query);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            logger.error("Error searching classes", e);
            return ResponseEntity.ok(new ArrayList<>());
        }
    }

    @PostMapping("/classes/{projectId}")
    public ResponseEntity<Map<String, String>> createClass(
            @PathVariable String projectId,
            @RequestBody Map<String, String> classData) {
        logger.info("Creating class in project: {}", projectId);
        try {
            String className = classData.get("name");
            String parentIri = classData.get("parentIri");
            String classIri = ontologyIndexService.createClass(projectId, className, parentIri);
            Map<String, String> response = new HashMap<>();
            response.put("iri", classIri);
            response.put("message", "Class created successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error creating class", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/classes/{projectId}/{classIri}")
    public ResponseEntity<Map<String, String>> deleteClass(
            @PathVariable String projectId,
            @PathVariable String classIri) {
        logger.info("Deleting class {} in project: {}", classIri, projectId);
        try {
            ontologyIndexService.deleteClass(projectId, classIri);
            Map<String, String> response = new HashMap<>();
            response.put("message", "Class deleted successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error deleting class", e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/properties/{projectId}")
    public ResponseEntity<Map<String, Object>> getAllProperties(@PathVariable String projectId) {
        logger.info("Fetching all properties for project: {}", projectId);
        try {
            List<PropertyDto> properties = ontologyIndexService.getAllProperties(projectId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", properties);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching properties", e);
            return ResponseEntity.status(500).body(createErrorResponse("Failed to fetch properties"));
        }
    }

    @GetMapping("/object-properties/tree/{projectId}")
    public ResponseEntity<List<PropertyDto>> getObjectPropertyHierarchy(@PathVariable String projectId) {
        logger.info("Fetching object property hierarchy for project: {}", projectId);
        try {
            List<PropertyDto> hierarchy = ontologyIndexService.getObjectPropertyHierarchy(projectId);
            return ResponseEntity.ok(hierarchy);
        } catch (Exception e) {
            logger.error("Error fetching object property hierarchy", e);
            return ResponseEntity.ok(new ArrayList<>());
        }
    }

    @GetMapping("/data-properties/tree/{projectId}")
    public ResponseEntity<List<PropertyDto>> getDataPropertyHierarchy(@PathVariable String projectId) {
        logger.info("Fetching data property hierarchy for project: {}", projectId);
        try {
            List<PropertyDto> hierarchy = ontologyIndexService.getDataPropertyHierarchy(projectId);
            return ResponseEntity.ok(hierarchy);
        } catch (Exception e) {
            logger.error("Error fetching data property hierarchy", e);
            return ResponseEntity.ok(new ArrayList<>());
        }
    }

    @GetMapping("/individuals/{projectId}")
    public ResponseEntity<Map<String, Object>> getAllIndividuals(@PathVariable String projectId) {
        logger.info("Fetching all individuals for project: {}", projectId);
        try {
            List<IndividualDto> individuals = ontologyIndexService.getAllIndividuals(projectId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", individuals);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching individuals", e);
            return ResponseEntity.status(500).body(createErrorResponse("Failed to fetch individuals"));
        }
    }

    @GetMapping("/annotation-properties/{projectId}")
    public ResponseEntity<Map<String, Object>> getAllAnnotationProperties(@PathVariable String projectId) {
        logger.info("Fetching all annotation properties for project: {}", projectId);
        try {
            List<AnnotationPropertyDto> properties = ontologyIndexService.getAllAnnotationProperties(projectId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", properties);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching annotation properties", e);
            return ResponseEntity.status(500).body(createErrorResponse("Failed to fetch annotation properties"));
        }
    }

    @GetMapping("/datatypes/{projectId}")
    public ResponseEntity<Map<String, Object>> getAllDatatypes(@PathVariable String projectId) {
        logger.info("Fetching all datatypes for project: {}", projectId);
        try {
            List<DatatypeDto> datatypes = ontologyIndexService.getAllDatatypes(projectId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", datatypes);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching datatypes", e);
            return ResponseEntity.status(500).body(createErrorResponse("Failed to fetch datatypes"));
        }
    }

    @GetMapping("/classes/usage/{projectId}")
    public ResponseEntity<UsageInfoDto> getClassUsage(
            @PathVariable String projectId,
            @RequestParam String classIri) {
        logger.info("Fetching usage for class: {} in project: {}", classIri, projectId);
        try {
            UsageInfoDto usage = ontologyIndexService.getClassUsage(projectId, classIri);
            return ResponseEntity.ok(usage);
        } catch (Exception e) {
            logger.error("Error fetching class usage", e);
            return ResponseEntity.internalServerError().build();
        }
    }

private void createOrUpdateProject(String projectId, String filename, String gridfsFileId, String status, String message) {
    try {
        Query query = new Query(Criteria.where("_id").is(projectId));
        Update update = new Update()
                .set("status", status)
                .set("statusMessage", message)
                .set("filename", filename)
                .set("lastUpdated", new Date())
                .setOnInsert("createdAt", new Date())
                .setOnInsert("projectId", projectId);

        if (gridfsFileId != null) {
            update.set("gridfsFileId", gridfsFileId); // <-- This is the new part
        }

        mongoTemplate.upsert(query, update, "projects");

        logger.info("Created/Updated project document for: {}", projectId);
    } catch (Exception e) {
        logger.error("Failed to create/update project document for: {}", projectId, e);
    }
}

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("error", message);
        return error;
    }

    public static class TreeNode {
        private String id;
        private String label;
        private List<TreeNode> children;
        private Map<String, String> annotations;
        private Boolean hasChildren;

        public TreeNode() {}

        public TreeNode(String id, String label, List<TreeNode> children, Map<String, String> annotations) {
            this.id = id;
            this.label = label;
            this.children = children;
            this.annotations = annotations;
        }
        
        public TreeNode(String id, String label, List<TreeNode> children, Map<String, String> annotations, Boolean hasChildren) {
            this.id = id;
            this.label = label;
            this.children = children;
            this.annotations = annotations;
            this.hasChildren = hasChildren;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public List<TreeNode> getChildren() { return children; }
        public void setChildren(List<TreeNode> children) { this.children = children; }
        public Map<String, String> getAnnotations() { return annotations; }
        public void setAnnotations(Map<String, String> annotations) { this.annotations = annotations; }
        public Boolean getHasChildren() { return hasChildren; }
        public void setHasChildren(Boolean hasChildren) { this.hasChildren = hasChildren; }

        public void addChild(TreeNode child) {
            if (this.children == null) {
                this.children = new ArrayList<>();
            }
            this.children.add(child);
        }
    }
}