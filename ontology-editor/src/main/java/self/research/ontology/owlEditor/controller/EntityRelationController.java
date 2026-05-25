package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Single endpoint for add / edit / delete of any entity relation.
 * PUT /api/ontology/{projectId}/relation
 *
 * For "edit": builds [deleteOp, addOp] and passes both to OntologyMutationService.apply()
 * so the entire replace happens in one SPARQL UPDATE — no race condition, no orphaned triples.
 */
@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class EntityRelationController {

    private static final Logger log = LoggerFactory.getLogger(EntityRelationController.class);

    private final OntologyMutationService mutationService;

    public EntityRelationController(OntologyMutationService mutationService) {
        this.mutationService = mutationService;
    }

    @PutMapping("/{projectId}/relation")
    public ResponseEntity<?> editRelation(
            @PathVariable String projectId,
            @RequestBody RelationRequest req) {

        log.info("[RELATION] op={} entity={} rel={} old={} new={}",
                req.operation(), req.entityIri(), req.relationshipType(),
                req.oldTargetIri(), req.targetIri());

        try {
            List<MutationOp> ops = buildOps(req);
            if (ops.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "No operations generated for request"));
            }
            mutationService.apply(projectId, ops);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            log.warn("[RELATION] Bad request for project={}: {}", projectId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("[RELATION] Failed for project={}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(Map.of("success", false, "message", "Failed to apply relation operation"));
        }
    }

    private List<MutationOp> buildOps(RelationRequest req) {
        List<MutationOp> ops = new ArrayList<>();
        boolean isEdit   = "edit".equals(req.operation());
        boolean isDelete = "delete".equals(req.operation());
        boolean isAdd    = "add".equals(req.operation());

        // Simple IRI→IRI edit on class axioms: use atomic DELETE+INSERT+WHERE
        if (isEdit
                && req.oldTargetIri() != null && req.targetIri() != null
                && req.oldRestrictionData() == null && req.restrictionData() == null
                && isClassAxiomRelation(req.relationshipType())) {
            ops.add(buildAtomicUpdateOp(req));
            return ops;
        }

        if (isEdit || isDelete) ops.add(buildSingleOp(req, true));   // delete old value
        if (isEdit || isAdd)    ops.add(buildSingleOp(req, false));  // add new value
        return ops;
    }

    private boolean isClassAxiomRelation(String rel) {
        return "subClassOf".equals(rel) || "equivalentClass".equals(rel) || "disjointWith".equals(rel);
    }

    /** Atomic DELETE+INSERT+WHERE for simple IRI class-axiom edits. */
    private MutationOp buildAtomicUpdateOp(RelationRequest req) {
        String type = switch (req.relationshipType()) {
            case "subClassOf"      -> "updateSubClassOf";
            case "equivalentClass" -> "updateEquivalentClass";
            case "disjointWith"    -> "updateDisjointWith";
            default -> throw new IllegalArgumentException("No atomic update op for: " + req.relationshipType());
        };
        // Convention in OntologyMutationService: value = old IRI, target = new IRI
        return new MutationOp(type, req.entityIri(), null, null, null,
                req.oldTargetIri(), req.targetIri(), null, null, null, null, null, null, null);
    }

    /** Build a single add or delete op for any relation type. */
    private MutationOp buildSingleOp(RelationRequest req, boolean isDeleteOp) {
        String rel    = req.relationshipType();
        String iri    = req.entityIri();
        String target = isDeleteOp ? req.oldTargetIri() : req.targetIri();
        RestrictionData rd = isDeleteOp ? req.oldRestrictionData() : req.restrictionData();

        return switch (rel) {

            // ── Property relations ──────────────────────────────────────────────
            case "domain" -> {
                if (!isDeleteOp && rd != null) {
                    yield new MutationOp("addPropertyDomain", iri, null, null,
                            rd.propertyIri(), null, rd.fillerIri(), null,
                            rd.restrictionType(), rd.cardinality(),
                            rd.isDataRestriction() ? "DataRestriction" : "ObjectRestriction",
                            null, null, null);
                }
                yield new MutationOp(isDeleteOp ? "deletePropertyDomain" : "addPropertyDomain",
                        iri, null, null, null, null, target, null, null, null, null, null, null, null);
            }
            case "range" -> {
                if (!isDeleteOp && rd != null) {
                    yield new MutationOp("addPropertyRange", iri, null, null,
                            rd.propertyIri(), null, rd.fillerIri(), null,
                            rd.restrictionType(), rd.cardinality(),
                            rd.isDataRestriction() ? "DataRestriction" : "ObjectRestriction",
                            null, null, null);
                }
                yield new MutationOp(isDeleteOp ? "deletePropertyRange" : "addPropertyRange",
                        iri, null, null, null, null, target, null, null, null, null, null, null, null);
            }
            case "subProperty" -> new MutationOp(
                    isDeleteOp ? "deleteSubPropertyOf" : "addSubPropertyOf",
                    iri, null, null, null, null, target, null, null, null, null, null, null, null);
            case "inverse" -> new MutationOp(
                    isDeleteOp ? "deleteInverseProperty" : "addInverseProperty",
                    iri, null, null, null, null, target, null, null, null, null, null, null, null);
            case "disjoint" -> new MutationOp(
                    isDeleteOp ? "deleteDisjointProperty" : "addDisjointProperty",
                    iri, null, null, null, null, target, null, null, null, null, null, null, null);
            case "equivalent" -> new MutationOp(
                    isDeleteOp ? "deleteEquivalentProperty" : "addEquivalentProperty",
                    iri, null, null, null, null, target, null, null, null, null, null, null, null);

            // ── Class axiom relations ────────────────────────────────────────────
            case "subClassOf" -> {
                if (rd != null) {
                    String opType = isDeleteOp
                            ? (rd.isDataRestriction() ? "deleteDataRestriction" : "deleteObjectRestriction")
                            : (rd.isDataRestriction() ? "addDataRestriction"    : "addObjectRestriction");
                    yield new MutationOp(opType, iri, null, null, rd.propertyIri(), null, rd.fillerIri(), null,
                            rd.restrictionType(), rd.cardinality(), "SubClassOf", null, null, null);
                }
                if (!isDeleteOp && req.memberIris() != null && !req.memberIris().isEmpty()) {
                    String members  = String.join(",", req.memberIris());
                    String addType  = "union".equals(req.expressionType()) ? "addUnion" : "addIntersection";
                    yield new MutationOp(addType, iri, null, null, null, members, null, null, null, null,
                            "SubClassOf", null, null, null);
                }
                yield new MutationOp(isDeleteOp ? "deleteSubClassOf" : "addSubClassOf",
                        iri, null, null, null, null, target, null, null, null, null, null, null, null);
            }
            case "equivalentClass" -> {
                if (rd != null) {
                    String opType = isDeleteOp
                            ? (rd.isDataRestriction() ? "deleteDataRestriction" : "deleteObjectRestriction")
                            : (rd.isDataRestriction() ? "addDataRestriction"    : "addObjectRestriction");
                    yield new MutationOp(opType, iri, null, null, rd.propertyIri(), null, rd.fillerIri(), null,
                            rd.restrictionType(), rd.cardinality(), "EquivalentTo", null, null, null);
                }
                if (!isDeleteOp && req.memberIris() != null && !req.memberIris().isEmpty()) {
                    String members = String.join(",", req.memberIris());
                    String addType = "union".equals(req.expressionType()) ? "addUnion" : "addIntersection";
                    yield new MutationOp(addType, iri, null, null, null, members, null, null, null, null,
                            "EquivalentTo", null, null, null);
                }
                yield new MutationOp(isDeleteOp ? "deleteEquivalentClass" : "addEquivalentClass",
                        iri, null, null, null, null, target, null, null, null, null, null, null, null);
            }
            case "disjointWith" -> {
                if (rd != null) {
                    String opType = isDeleteOp
                            ? (rd.isDataRestriction() ? "deleteDataRestriction" : "deleteObjectRestriction")
                            : (rd.isDataRestriction() ? "addDataRestriction"    : "addObjectRestriction");
                    yield new MutationOp(opType, iri, null, null, rd.propertyIri(), null, rd.fillerIri(), null,
                            rd.restrictionType(), rd.cardinality(), "DisjointWith", null, null, null);
                }
                yield new MutationOp(isDeleteOp ? "deleteDisjointWith" : "addDisjointWith",
                        iri, null, null, null, null, target, null, null, null, null, null, null, null);
            }

            // ── Individual relations ──────────────────────────────────────────────
            case "sameAs" -> new MutationOp(
                    isDeleteOp ? "deleteSameIndividual" : "addSameIndividual",
                    iri, null, null, null, null, target, null, null, null, null, null, null, null);
            case "differentFrom" -> new MutationOp(
                    isDeleteOp ? "deleteDifferentIndividual" : "addDifferentIndividual",
                    iri, null, null, null, null, target, null, null, null, null, null, null, null);

            default -> throw new IllegalArgumentException("Unsupported relationshipType: " + rel);
        };
    }

    public record RestrictionData(
            String propertyIri,
            String restrictionType,
            String fillerIri,
            Integer cardinality,
            boolean isDataRestriction) {}

    public record RelationRequest(
            String operation,           // "add" | "edit" | "delete"
            String entityIri,           // subject entity IRI
            String relationshipType,    // domain|range|subProperty|inverse|disjoint|equivalent|
                                        // subClassOf|equivalentClass|disjointWith|sameAs|differentFrom
            String targetIri,           // new value (add / edit)
            String oldTargetIri,        // old value (edit / delete)
            String userId,
            String username,
            RestrictionData restrictionData,     // new restriction (add / edit)
            RestrictionData oldRestrictionData,  // old restriction (edit / delete)
            List<String> memberIris,    // intersection or union members (add / edit)
            String expressionType       // "intersection" | "union"
    ) {}
}
