package self.research.ontology.owlEditor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import self.research.ontology.owlEditor.service.OntologyMutationService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(origins = "*")
public class OntologyCrudController {

    private final OntologyMutationService mut;

    public OntologyCrudController(OntologyMutationService mut) {
        this.mut = mut;
    }

    // ========== CLASSES ==========

    @PostMapping("/classes/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> createClass(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body) {
        String iri = (String) body.get("iri");
        String label = (String) body.getOrDefault("label", "");
        String parentIri = (String) body.getOrDefault("parentIri", "");
        
        return mut.createClass(projectId, iri, label, parentIri)
                .thenReturn(ResponseEntity.ok(Map.of(
                    "success", true, 
                    "iri", iri != null ? iri : "", 
                    "label", label
                )));
    }

    @PutMapping("/classes/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> updateClass(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body) {
        String iri = (String) body.get("iri");
        String label = (String) body.get("label");
        String parentIri = (String) body.get("parentIri");
        
        Mono<Void> operation = Mono.empty();
        if (label != null) {
            operation = operation.then(mut.updateClassLabel(projectId, iri, label));
        }
        if (parentIri != null) {
            operation = operation.then(mut.setClassParent(projectId, iri, parentIri));
        }
        
        return operation.thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    @DeleteMapping("/classes/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> deleteClass(
            @PathVariable String projectId,
            @RequestParam String iri,
            @RequestParam(defaultValue = "false") boolean force) {
        
        return mut.canDeleteClass(projectId, iri).flatMap(canDelete -> {
            if (!canDelete && !force) {
                return Mono.just(ResponseEntity.status(409)
                        .body(Map.of("success", false, 
                                   "error", "Class has subclasses or instances")));
            }
            return mut.deleteClass(projectId, iri)
                    .thenReturn(ResponseEntity.ok(Map.of("success", true)));
        });
    }

    // ========== PROPERTIES ==========

    @PostMapping("/properties/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> createProperty(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body) {
        String type = (String) body.getOrDefault("type", "ObjectProperty");
        String iri = (String) body.get("iri");
        String label = (String) body.get("label");
        @SuppressWarnings("unchecked")
        List<String> domains = (List<String>) body.get("domains");
        @SuppressWarnings("unchecked")
        List<String> ranges = (List<String>) body.get("ranges");
        
        return mut.createProperty(projectId, type, iri, label, domains, ranges)
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    @PutMapping("/properties/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> updateProperty(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body) {
        String iri = (String) body.get("iri");
        String label = (String) body.get("label");
        @SuppressWarnings("unchecked")
        List<String> domains = (List<String>) body.get("domains");
        @SuppressWarnings("unchecked")
        List<String> ranges = (List<String>) body.get("ranges");
        
        Mono<Void> operation = Mono.empty();
        if (label != null) {
            operation = operation.then(mut.updatePropertyLabel(projectId, iri, label));
        }
        if (domains != null) {
            operation = operation.then(mut.replaceDomains(projectId, iri, domains));
        }
        if (ranges != null) {
            operation = operation.then(mut.replaceRanges(projectId, iri, ranges));
        }
        
        return operation.thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    @DeleteMapping("/properties/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> deleteProperty(
            @PathVariable String projectId,
            @RequestParam String iri) {
        return mut.deleteProperty(projectId, iri)
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    // ========== INDIVIDUALS ==========

    @PostMapping("/individuals/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> createIndividual(
            @PathVariable String projectId, 
            @RequestBody Map<String, Object> body) {
        String iri = (String) body.get("iri");
        String label = (String) body.get("label");
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) body.get("types");
        
        return mut.createIndividual(projectId, iri, label, types)
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    @PutMapping("/individuals/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> updateIndividual(
            @PathVariable String projectId, 
            @RequestBody Map<String, Object> body) {
        String iri = (String) body.get("iri");
        String label = (String) body.get("label");
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) body.get("types");
        
        Mono<Void> operation = Mono.empty();
        if (label != null) {
            operation = operation.then(mut.updateIndividualLabel(projectId, iri, label));
        }
        if (types != null) {
            operation = operation.then(mut.setIndividualTypes(projectId, iri, types));
        }
        
        return operation.thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    @DeleteMapping("/individuals/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> deleteIndividual(
            @PathVariable String projectId, 
            @RequestParam String iri) {
        return mut.deleteIndividual(projectId, iri)
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    // ========== PROPERTY ASSERTIONS ==========

    @PostMapping("/assertions/{projectId}/add")
    public Mono<ResponseEntity<Map<String,Object>>> addAssertion(
            @PathVariable String projectId, 
            @RequestBody Map<String, String> body) {
        return mut.addPropertyAssertion(projectId, 
                body.get("subjectIri"), 
                body.get("propertyIri"), 
                body.get("objectIri"))
            .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    @PostMapping("/assertions/{projectId}/remove")
    public Mono<ResponseEntity<Map<String,Object>>> removeAssertion(
            @PathVariable String projectId, 
            @RequestBody Map<String, String> body) {
        return mut.removePropertyAssertion(projectId,
                body.get("subjectIri"), 
                body.get("propertyIri"), 
                body.get("objectIri"))
            .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    @GetMapping("/assertions/{projectId}")
    public Mono<ResponseEntity<JsonNode>> getAssertions(
            @PathVariable String projectId, 
            @RequestParam String individualIri) {
        return mut.getIndividualAssertions(projectId, individualIri)
                .map(ResponseEntity::ok);
    }

    // ========== ANNOTATIONS ==========

    @PostMapping("/annotations/{projectId}/add")
    public Mono<ResponseEntity<Map<String,Object>>> addAnnotation(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body) {
        String subject = (String) body.get("subjectIri");
        String prop = (String) body.get("propertyIri");
        String value = (String) body.get("value");
        String lang = (String) body.get("lang");
        String datatype = (String) body.get("datatypeIri");
        
        return mut.addAnnotation(projectId, subject, prop, value, lang, datatype)
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    @PostMapping("/annotations/{projectId}/delete")
    public Mono<ResponseEntity<Map<String,Object>>> deleteAnnotation(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body) {
        return mut.deleteAnnotation(projectId,
                (String) body.get("subjectIri"),
                (String) body.get("propertyIri"),
                (String) body.get("value"))
            .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    // ========== AXIOMS ==========

    @PostMapping("/axioms/{projectId}/equivalent-classes")
    public Mono<ResponseEntity<Map<String,Object>>> addEquivalentClasses(
            @PathVariable String projectId, 
            @RequestBody Map<String, String> body) {
        return mut.addEquivalentClasses(projectId, body.get("class1"), body.get("class2"))
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    @PostMapping("/axioms/{projectId}/disjoint-classes")
    public Mono<ResponseEntity<Map<String,Object>>> addDisjointClasses(
            @PathVariable String projectId, 
            @RequestBody Map<String, List<String>> body) {
        return mut.addDisjointClasses(projectId, body.get("classes"))
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    @PostMapping("/axioms/{projectId}/equivalent-properties")
    public Mono<ResponseEntity<Map<String,Object>>> addEquivalentProperties(
            @PathVariable String projectId, 
            @RequestBody Map<String, String> body) {
        return mut.addEquivalentProperties(projectId, body.get("property1"), body.get("property2"))
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    // ========== METADATA ==========

    @PutMapping("/metadata/{projectId}")
    public Mono<ResponseEntity<Map<String,Object>>> updateMetadata(
            @PathVariable String projectId, 
            @RequestBody Map<String, Object> body) {
        String versionIri = (String) body.get("versionIri");
        @SuppressWarnings("unchecked")
        List<String> imports = (List<String>) body.get("imports");
        @SuppressWarnings("unchecked")
        Map<String,String> annotations = (Map<String,String>) body.get("annotations");
        
        return mut.updateOntologyMetadata(projectId, versionIri, imports, annotations)
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }
}