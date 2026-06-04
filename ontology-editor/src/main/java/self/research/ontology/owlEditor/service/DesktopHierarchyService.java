package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.hierarchy.HierarchySnapshotBuilder;
import self.research.ontology.owlEditor.hierarchy.OntologyMetricsComputer;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Desktop-only hierarchy service using OWLAPI in-memory model.
 */
@Service
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
public class DesktopHierarchyService {

    private static final Logger log = LoggerFactory.getLogger(DesktopHierarchyService.class);

    @Autowired
    private ProjectOntologyCache ontologyCache;

    @Autowired
    private HierarchySnapshotBuilder snapshotBuilder;

    @Autowired
    private OntologyMetricsComputer metricsComputer;

    public boolean hasOntology(String projectId) {
        return ontologyCache.has(projectId);
    }

    public Map<String, Object> declarationCounts(String projectId) {
        return ontologyCache.get(projectId)
            .map(c -> metricsComputer.compute(c.ontology(), c.reasoner()))
            .orElse(Collections.emptyMap());
    }

    public List<OntologyDto.TreeNode> topLevelClasses(String projectId, int limit) {
        return ontologyCache.get(projectId)
            .map(c -> snapshotBuilder.buildTopLevel(c.ontology(), c.reasoner(), limit))
            .orElse(Collections.emptyList());
    }

    public int topLevelClassTotal(String projectId) {
        return ontologyCache.get(projectId)
            .map(c -> snapshotBuilder.countTopLevelCandidates(c.ontology(), c.reasoner()))
            .orElse(0);
    }

    public List<OntologyDto.TreeNode> children(String projectId, String parentIri, int limit, int offset) {
        return ontologyCache.get(projectId)
            .map(c -> snapshotBuilder.buildChildren(c.ontology(), c.reasoner(), parentIri, limit, offset))
            .orElse(Collections.emptyList());
    }

    public Map<String, Object> classDetails(String projectId, String classIri) {
        return ontologyCache.get(projectId)
            .map(c -> buildClassDetails(c.ontology(), c.reasoner(), classIri))
            .orElse(Collections.emptyMap());
    }

    public Map<String, Object> classUsage(String projectId, String classIri) {
        return ontologyCache.get(projectId)
            .map(c -> buildClassUsage(c.ontology(), classIri))
            .orElse(Collections.emptyMap());
    }

    private Map<String, Object> buildClassDetails(OWLOntology ont, OWLReasoner reasoner, String classIri) {
        long start = System.currentTimeMillis();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", classIri);

        org.semanticweb.owlapi.model.parameters.Imports imp = org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED;
        IRI rdfsLabel = IRI.create("http://www.w3.org/2000/01/rdf-schema#label");
        Map<String, Object> annotations = new LinkedHashMap<>();
        ont.annotationAssertionAxioms(cls.getIRI(), imp).forEach(ax -> {
            String prop = ax.getProperty().getIRI().toString();
            ax.getValue().asLiteral().ifPresent(lit ->
                annotations.putIfAbsent(prop, lit.getLiteral()));
            ax.getValue().asIRI().ifPresent(iri ->
                annotations.putIfAbsent(prop, iri.toString()));
        });
        details.put("annotations", annotations);

        List<Map<String, String>> subClassOfAxioms = ont.subClassAxiomsForSubClass(cls)
            .map(ax -> ax.getSuperClass())
            .filter(ce -> !ce.isAnonymous() && !ce.isOWLThing() && !ce.isOWLNothing())
            .map(ce -> labeledEntry(ont, ce.asOWLClass()))
            .collect(Collectors.toList());
        details.put("subClassOfAxioms", subClassOfAxioms);

        List<Map<String, String>> equivalentClassesAxioms = ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && !ce.isAnonymous())
            .map(ce -> labeledEntry(ont, ce.asOWLClass()))
            .collect(Collectors.toList());
        details.put("equivalentClassesAxioms", equivalentClassesAxioms);

        List<Map<String, String>> disjointClassesAxioms = ont.disjointClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && !ce.isAnonymous())
            .map(ce -> labeledEntry(ont, ce.asOWLClass()))
            .collect(Collectors.toList());
        details.put("disjointClassesAxioms", disjointClassesAxioms);

        List<Map<String, Object>> restrictions = ont.subClassAxiomsForSubClass(cls)
            .map(ax -> ax.getSuperClass())
            .filter(OWLClassExpression::isAnonymous)
            .flatMap(ce -> extractRestrictions(ont, ce))
            .collect(Collectors.toList());
        details.put("restrictions", restrictions);

        List<Map<String, String>> unionMembers = ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && ce instanceof OWLObjectUnionOf)
            .flatMap(ce -> ((OWLObjectUnionOf) ce).operands())
            .filter(op -> !op.isAnonymous())
            .map(op -> labeledEntry(ont, op.asOWLClass()))
            .collect(Collectors.toList());
        details.put("unionOfMembers", unionMembers);

        List<Map<String, String>> intersectionMembers = ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && ce instanceof OWLObjectIntersectionOf)
            .flatMap(ce -> ((OWLObjectIntersectionOf) ce).operands())
            .filter(op -> !op.isAnonymous())
            .map(op -> labeledEntry(ont, op.asOWLClass()))
            .collect(Collectors.toList());
        details.put("intersectionOfMembers", intersectionMembers);

        List<Map<String, String>> directSubclasses = reasoner.getSubClasses(cls, true)
            .entities()
            .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
            .map(c -> labeledEntry(ont, c))
            .collect(Collectors.toList());
        details.put("directSubclasses", directSubclasses);

        log.debug("[Desktop] classDetails({}) in {}ms", classIri, System.currentTimeMillis() - start);
        return details;
    }

    private Stream<Map<String, Object>> extractRestrictions(OWLOntology ont, OWLClassExpression ce) {
        if (ce instanceof OWLRestriction) {
            OWLRestriction r = (OWLRestriction) ce;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("property", r.getProperty().isAnonymous() ? "" : r.getProperty().asOWLObjectProperty().getIRI().toString());
            m.put("type", ce.getClassExpressionType().getName());
            return Stream.of(m);
        }
        return Stream.empty();
    }

    private Map<String, Object> buildClassUsage(OWLOntology ont, String classIri) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("superClasses", ont.subClassAxiomsForSubClass(cls)
            .map(ax -> ax.getSuperClass()).filter(ce -> !ce.isAnonymous())
            .map(ce -> labeledEntry(ont, ce.asOWLClass())).collect(Collectors.toList()));
        result.put("subClasses", ont.subClassAxiomsForSuperClass(cls)
            .map(ax -> ax.getSubClass()).filter(ce -> !ce.isAnonymous())
            .map(ce -> labeledEntry(ont, ce.asOWLClass())).collect(Collectors.toList()));
        return result;
    }

    private Map<String, String> labeledEntry(OWLOntology ont, OWLClass cls) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("iri", cls.getIRI().toString());
        m.put("label", cls.getIRI().getShortForm());
        return m;
    }
}
