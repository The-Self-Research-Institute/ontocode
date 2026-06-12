package self.research.ontology.owlEditor.controller;

import org.semanticweb.owlapi.model.OWLClassExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.ManchesterExpressionService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.Map;

/**
 * Manchester OWL Syntax expression parser for UI validation (GCA editor, class expressions, etc.).
 */
@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class ExpressionParseController {

    private static final Logger log = LoggerFactory.getLogger(ExpressionParseController.class);

    private final ManchesterExpressionService manchesterExpressionService;
    private final CollaborativeEditService collaborativeEditService;

    public ExpressionParseController(ManchesterExpressionService manchesterExpressionService,
                                     CollaborativeEditService collaborativeEditService) {
        this.manchesterExpressionService = manchesterExpressionService;
        this.collaborativeEditService = collaborativeEditService;
    }

    @PostMapping("/{projectId}/expression/parse")
    public ResponseEntity<?> parseExpression(@PathVariable String projectId,
                                             @RequestBody ParseExpressionRequest request) {
        if (request == null || request.expression == null || request.expression.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "expression is required"));
        }

        try {
            OWLClassExpression classExpression =
                    manchesterExpressionService.parseClassExpression(projectId, request.expression.trim());
            if (classExpression == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Could not parse expression"));
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "manchester", classExpression.toString(),
                    "type", classExpression.getClass().getSimpleName(),
                    "isAnonymous", classExpression.isAnonymous()));
        } catch (Exception e) {
            log.warn("Expression parse failed for project {}: {}", projectId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Parse failed"));
        }
    }

    @PostMapping("/{projectId}/expression/add-property-axiom")
    public ResponseEntity<?> addPropertyExpressionAxiom(@PathVariable String projectId,
                                                        @RequestBody AddPropertyAxiomRequest request,
                                                        @RequestParam(required = false) String userId,
                                                        @RequestParam(required = false) String username) {
        if (request == null || request.propertyIri == null || request.propertyIri.isBlank()
                || request.expression == null || request.expression.isBlank()
                || request.relationType == null || request.relationType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "propertyIri, relationType, and expression are required"));
        }
        try {
            boolean isDataProperty = Boolean.TRUE.equals(request.isDataProperty);
            String relation = request.relationType.trim();
            if ("Domain".equalsIgnoreCase(relation)) {
                manchesterExpressionService.addPropertyDomainAxiom(
                        projectId, request.propertyIri, request.expression, isDataProperty);
            } else if ("Range".equalsIgnoreCase(relation)) {
                manchesterExpressionService.addPropertyRangeAxiom(
                        projectId, request.propertyIri, request.expression, isDataProperty);
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "relationType must be Domain or Range"));
            }
            collaborativeEditService.broadcastMutation(projectId,
                    new OntologyMutationService.MutationOp(
                            "Domain".equalsIgnoreCase(relation) ? "addPropertyDomain" : "addPropertyRange",
                            request.propertyIri, null, null, null, request.expression,
                            null, null, null, null, null, null, null, null),
                    userId != null ? userId : "anonymous",
                    username != null ? username : "Anonymous");
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to add property expression axiom for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to add property expression"));
        }
    }

    @PostMapping("/{projectId}/expression/delete-property-axiom")
    public ResponseEntity<?> deletePropertyExpressionAxiom(@PathVariable String projectId,
                                                         @RequestBody AddPropertyAxiomRequest request,
                                                         @RequestParam(required = false) String userId,
                                                         @RequestParam(required = false) String username) {
        if (request == null || request.propertyIri == null || request.propertyIri.isBlank()
                || request.expression == null || request.expression.isBlank()
                || request.relationType == null || request.relationType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "propertyIri, relationType, and expression are required"));
        }
        try {
            boolean isDataProperty = Boolean.TRUE.equals(request.isDataProperty);
            String relation = request.relationType.trim();
            if ("Domain".equalsIgnoreCase(relation)) {
                manchesterExpressionService.deletePropertyDomainAxiom(
                        projectId, request.propertyIri, request.expression, isDataProperty);
            } else if ("Range".equalsIgnoreCase(relation)) {
                manchesterExpressionService.deletePropertyRangeAxiom(
                        projectId, request.propertyIri, request.expression, isDataProperty);
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "relationType must be Domain or Range"));
            }
            collaborativeEditService.broadcastMutation(projectId,
                    new OntologyMutationService.MutationOp(
                            "Domain".equalsIgnoreCase(relation) ? "deletePropertyDomain" : "deletePropertyRange",
                            request.propertyIri, null, null, null, request.expression,
                            null, null, null, null, null, null, null, null),
                    userId != null ? userId : "anonymous",
                    username != null ? username : "Anonymous");
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete property expression axiom for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to delete property expression"));
        }
    }

    @PostMapping("/{projectId}/expression/add-gca")
    public ResponseEntity<?> addGeneralClassAxiom(@PathVariable String projectId,
                                                  @RequestBody AddGcaRequest request,
                                                  @RequestParam(required = false) String userId,
                                                  @RequestParam(required = false) String username) {
        if (request == null || request.subClassExpression == null || request.subClassExpression.isBlank()
                || request.superClassExpression == null || request.superClassExpression.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "subClassExpression and superClassExpression are required"));
        }
        try {
            manchesterExpressionService.addGeneralClassAxiom(
                    projectId, request.subClassExpression.trim(), request.superClassExpression.trim());
            collaborativeEditService.broadcastMutation(projectId,
                    new OntologyMutationService.MutationOp(
                            "addGCA", null, null, null, null,
                            request.subClassExpression.trim() + " SubClassOf " + request.superClassExpression.trim(),
                            request.superClassExpression.trim(), null, null, null, null, null, null, null),
                    userId != null ? userId : "anonymous",
                    username != null ? username : "Anonymous");
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to add GCA for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to add GCA"));
        }
    }

    @PostMapping("/{projectId}/expression/add-class-axiom")
    public ResponseEntity<?> addClassExpressionAxiom(@PathVariable String projectId,
                                                     @RequestBody AddClassAxiomRequest request,
                                                     @RequestParam(required = false) String userId,
                                                     @RequestParam(required = false) String username) {
        if (request == null || request.classIri == null || request.classIri.isBlank()
                || request.expression == null || request.expression.isBlank()
                || request.axiomType == null || request.axiomType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "classIri, axiomType, and expression are required"));
        }
        try {
            manchesterExpressionService.addClassExpressionAxiom(
                    projectId, request.classIri, request.axiomType, request.expression);
            collaborativeEditService.broadcastMutation(projectId,
                    new OntologyMutationService.MutationOp(
                            "addClassExpression", request.classIri, null, null, null, request.expression,
                            null, request.classIri, null, null, request.axiomType, null, null, null),
                    userId != null ? userId : "anonymous",
                    username != null ? username : "Anonymous");
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to add class expression axiom for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to add class expression"));
        }
    }

    public static class ParseExpressionRequest {
        public String expression;
    }

    public static class AddGcaRequest {
        public String subClassExpression;
        public String superClassExpression;
    }

    public static class AddClassAxiomRequest {
        public String classIri;
        public String axiomType;
        public String expression;
    }

    public static class AddPropertyAxiomRequest {
        public String propertyIri;
        public String relationType;
        public String expression;
        public Boolean isDataProperty;
    }
}
