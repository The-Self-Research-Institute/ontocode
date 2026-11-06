package self.research.ontology.owlEditor.service.search;

import self.research.ontology.owlEditor.service.search.EntitySearch.EntityType;
import self.research.ontology.owlEditor.service.search.EntitySearch.AnnotationValue;
import self.research.ontology.owlEditor.service.search.EntitySearch.SearchRequest;
import self.research.ontology.owlEditor.service.search.EntitySearch.EntityHit;
import self.research.ontology.owlEditor.service.search.EntitySearch.BuiltInEntities;
import self.research.ontology.owlEditor.service.search.EntitySearch.EntityMatching;
import self.research.ontology.owlEditor.service.search.EntitySearch.EntitySearchResult;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import self.research.ontology.owlEditor.hierarchy.HierarchySnapshotBuilder;
import self.research.ontology.owlEditor.service.ClassSearchResult;
import self.research.ontology.owlEditor.service.owlapi.OwlApiQuerySupport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

public final class EntitySearchOwlApi {

    private final HierarchySnapshotBuilder hierarchy;

    public EntitySearchOwlApi(HierarchySnapshotBuilder hierarchy) {
        this.hierarchy = hierarchy;
    }

    public Map<String, Object> search(OWLOntology ont, SearchRequest request, Imports imports) {
        EntitySearchResult.Builder out = EntitySearchResult.builder(request.limitPerType());
        if (request.matcher().isEmpty()) {
            return out.build().toResponse(List.of());
        }
        Map<String, String> propertyLabels = new HashMap<>();
        UnaryOperator<String> propertyLabel = property -> propertyLabels.computeIfAbsent(
                property, p -> OwlApiQuerySupport.getLabel(ont, IRI.create(p)));

        for (EntityType type : request.types()) {
            List<EntityMatching.Candidate> candidates = new ArrayList<>();
            for (OWLEntity entity : entities(ont, type, imports)) {
                String iri = entity.getIRI().toString();
                if (request.hideBuiltins() && BuiltInEntities.isBuiltIn(type, iri)) {
                    continue;
                }
                EntityMatching.Evaluation evaluation = EntityMatching.evaluate(
                        iri, annotationValues(ont, entity, imports), request, propertyLabel);
                if (evaluation.matches().isEmpty() || (request.hideDeprecated() && evaluation.deprecated())) {
                    continue;
                }
                candidates.add(new EntityMatching.Candidate(iri, evaluation.label(), evaluation.matches()));
            }
            EntityMatching.addSorted(out, type, candidates);
        }

        EntitySearchResult result = out.build();
        List<String> classHits = result.groups().get(EntityType.CLASS).stream().map(EntityHit::id).toList();
        return result.toResponse(classAncestors(ont, classHits, imports));
    }

    private List<Map<String, Object>> classAncestors(OWLOntology ont, List<String> classHits, Imports imports) {
        if (classHits.isEmpty()) {
            return List.of();
        }
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        Map<String, Set<String>> parentsOf = new HashMap<>();
        ArrayDeque<String> frontier = new ArrayDeque<>(classHits);
        while (!frontier.isEmpty()) {
            String iri = frontier.poll();
            if (parentsOf.containsKey(iri)) {
                continue;
            }
            Set<String> parents = hierarchy.namedParentIris(ont, df.getOWLClass(IRI.create(iri)), imports);
            parentsOf.put(iri, parents);
            frontier.addAll(parents);
        }
        return ClassSearchResult.ancestorsOf(classHits, parentsOf);
    }

    private static List<OWLEntity> entities(OWLOntology ont, EntityType type, Imports imports) {
        return switch (type) {
            case CLASS -> ont.getClassesInSignature(imports).stream()
                    .filter(c -> !HierarchySnapshotBuilder.isBuiltInOrPlaceholder(c) && !c.isOWLNothing())
                    .map(OWLEntity.class::cast)
                    .toList();
            case OBJECT_PROPERTY -> new ArrayList<OWLEntity>(ont.getObjectPropertiesInSignature(imports));
            case DATA_PROPERTY -> new ArrayList<OWLEntity>(ont.getDataPropertiesInSignature(imports));
            case ANNOTATION_PROPERTY -> new ArrayList<OWLEntity>(ont.getAnnotationPropertiesInSignature(imports));
            case DATATYPE -> new ArrayList<OWLEntity>(ont.getDatatypesInSignature(imports));
            case INDIVIDUAL -> new ArrayList<OWLEntity>(ont.getIndividualsInSignature(imports));
        };
    }

    private static List<AnnotationValue> annotationValues(OWLOntology ont, OWLEntity entity, Imports imports) {
        return ont.annotationAssertionAxioms(entity.getIRI(), imports)
                .map(EntitySearchOwlApi::toValue)
                .filter(Objects::nonNull)
                .toList();
    }

    private static AnnotationValue toValue(OWLAnnotationAssertionAxiom axiom) {
        String property = axiom.getProperty().getIRI().toString();
        return axiom.getValue().asLiteral()
                .map(literal -> new AnnotationValue(property, literal.getLiteral(), true))
                .or(() -> axiom.getValue().asIRI()
                        .map(iri -> new AnnotationValue(property, iri.toString(), false)))
                .orElse(null);
    }
}
