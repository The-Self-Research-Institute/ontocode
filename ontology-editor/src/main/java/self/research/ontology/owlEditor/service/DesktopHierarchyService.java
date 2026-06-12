package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.hierarchy.HierarchySnapshotBuilder;
import self.research.ontology.owlEditor.hierarchy.OntologyMetricsComputer;

import java.util.*;
import java.util.stream.Collectors;

/**
 * OWLAPI in-memory hierarchy (desktop + cloud fast-open).
 */
@Service
@Conditional(FastOpenCondition.class)
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
            .map(c -> c.assertedHierarchyOnly()
                    ? metricsComputer.computeAsserted(c.ontology())
                    : metricsComputer.compute(c.ontology(), c.reasoner()))
            .orElse(Collections.emptyMap());
    }

    public List<OntologyDto.TreeNode> topLevelClasses(String projectId, int limit, int offset) {
        return ontologyCache.get(projectId)
            .map(c -> c.assertedHierarchyOnly()
                    ? snapshotBuilder.buildTopLevelAsserted(c.ontology(), limit, offset)
                    : snapshotBuilder.buildTopLevel(c.ontology(), c.reasoner(), limit, offset))
            .orElse(Collections.emptyList());
    }

    public int topLevelClassTotal(String projectId) {
        return ontologyCache.get(projectId)
            .map(c -> c.assertedHierarchyOnly()
                    ? snapshotBuilder.countTopLevelAsserted(c.ontology())
                    : snapshotBuilder.countTopLevelCandidates(c.ontology(), c.reasoner()))
            .orElse(0);
    }

    public List<OntologyDto.TreeNode> children(String projectId, String parentIri, int limit, int offset) {
        return ontologyCache.get(projectId)
            .map(c -> {
                OWLReasoner r = c.reasoner();
                if (r == null) {
                    r = new org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory()
                            .createNonBufferingReasoner(c.ontology());
                }
                return snapshotBuilder.buildChildren(c.ontology(), r, parentIri, limit, offset);
            })
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
        Map<String, Object> annotations = new LinkedHashMap<>();
        ont.annotationAssertionAxioms(cls.getIRI(), imp).forEach(ax -> {
            String prop = ax.getProperty().getIRI().toString();
            ax.getValue().asLiteral().ifPresent(lit ->
                annotations.putIfAbsent(prop, lit.getLiteral()));
            ax.getValue().asIRI().ifPresent(iri ->
                annotations.putIfAbsent(prop, iri.toString()));
        });
        details.put("annotations", annotations);

        // subClassOfAxioms: ALL superclasses (named + restrictions) with Manchester syntax definition
        List<OWLClassExpression> subClassExprs = ont.subClassAxiomsForSubClass(cls)
            .map(ax -> ax.getSuperClass())
            .filter(ce -> !ce.isOWLThing() && !ce.isOWLNothing())
            .collect(Collectors.toList());
        List<Map<String, Object>> subClassOfAxioms = new ArrayList<>();
        for (int i = 0; i < subClassExprs.size(); i++) {
            Map<String, Object> m = classExpressionToAxiomMap(ont, subClassExprs.get(i), "sub_" + i);
            m.put("type", "SubClassOf");
            subClassOfAxioms.add(m);
        }
        details.put("subClassOfAxioms", subClassOfAxioms);

        // equivalentClassesAxioms: named + anonymous (restrictions, complex expressions)
        List<OWLClassExpression> eqExprs = ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls))
            .collect(Collectors.toList());
        List<Map<String, Object>> equivalentClassesAxioms = new ArrayList<>();
        for (int i = 0; i < eqExprs.size(); i++) {
            Map<String, Object> m = classExpressionToAxiomMap(ont, eqExprs.get(i), "eq_" + i);
            m.put("type", "EquivalentTo");
            equivalentClassesAxioms.add(m);
        }
        details.put("equivalentClassesAxioms", equivalentClassesAxioms);

        // disjointClassesAxioms: named disjoint classes only
        List<OWLClassExpression> disjointExprs = ont.disjointClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && !ce.isAnonymous())
            .collect(Collectors.toList());
        List<Map<String, Object>> disjointClassesAxioms = new ArrayList<>();
        for (int i = 0; i < disjointExprs.size(); i++) {
            Map<String, Object> m = classExpressionToAxiomMap(ont, disjointExprs.get(i), "dis_" + i);
            m.put("type", "DisjointWith");
            disjointClassesAxioms.add(m);
        }
        details.put("disjointClassesAxioms", disjointClassesAxioms);

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

        OWLReasoner r = reasoner;
        if (r == null) {
            r = new org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory()
                    .createNonBufferingReasoner(ont);
        }
        List<Map<String, String>> directSubclasses = r.getSubClasses(cls, true)
            .entities()
            .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
            .map(c -> labeledEntry(ont, c))
            .collect(Collectors.toList());
        details.put("directSubclasses", directSubclasses);

        log.debug("[Desktop] classDetails({}) in {}ms", classIri, System.currentTimeMillis() - start);
        return details;
    }

    private Map<String, Object> classExpressionToAxiomMap(OWLOntology ont, OWLClassExpression ce, String idPrefix) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definition", classExpressionToManchester(ont, ce));
        if (!ce.isAnonymous()) {
            m.put("id", ce.asOWLClass().getIRI().toString());
            m.put("isRestriction", false);
        } else if (ce instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom r = (OWLObjectSomeValuesFrom) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = r.getFiller().isAnonymous() ? "" : r.getFiller().asOWLClass().getIRI().toString();
                m.put("id", pIri + "_some_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "some"); m.put("fillerIri", fIri);
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLObjectAllValuesFrom) {
            OWLObjectAllValuesFrom r = (OWLObjectAllValuesFrom) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = r.getFiller().isAnonymous() ? "" : r.getFiller().asOWLClass().getIRI().toString();
                m.put("id", pIri + "_only_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "only"); m.put("fillerIri", fIri);
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLObjectMinCardinality) {
            OWLObjectMinCardinality r = (OWLObjectMinCardinality) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = (!r.getFiller().isAnonymous() && r.getFiller() instanceof OWLClass) ? r.getFiller().asOWLClass().getIRI().toString() : "";
                m.put("id", pIri + "_min" + r.getCardinality() + "_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "min"); m.put("fillerIri", fIri); m.put("cardinality", r.getCardinality());
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLObjectMaxCardinality) {
            OWLObjectMaxCardinality r = (OWLObjectMaxCardinality) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = (!r.getFiller().isAnonymous() && r.getFiller() instanceof OWLClass) ? r.getFiller().asOWLClass().getIRI().toString() : "";
                m.put("id", pIri + "_max" + r.getCardinality() + "_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "max"); m.put("fillerIri", fIri); m.put("cardinality", r.getCardinality());
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLObjectExactCardinality) {
            OWLObjectExactCardinality r = (OWLObjectExactCardinality) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = (!r.getFiller().isAnonymous() && r.getFiller() instanceof OWLClass) ? r.getFiller().asOWLClass().getIRI().toString() : "";
                m.put("id", pIri + "_exactly" + r.getCardinality() + "_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "exactly"); m.put("fillerIri", fIri); m.put("cardinality", r.getCardinality());
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLObjectHasValue) {
            OWLObjectHasValue r = (OWLObjectHasValue) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = r.getFiller().isAnonymous() ? "" : r.getFiller().asOWLNamedIndividual().getIRI().toString();
                m.put("id", pIri + "_value_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "value"); m.put("fillerIri", fIri);
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLDataSomeValuesFrom) {
            OWLDataSomeValuesFrom r = (OWLDataSomeValuesFrom) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = dataRangeToString(r.getFiller());
            m.put("id", pIri + "_data_some_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "some"); m.put("fillerIri", fStr);
        } else if (ce instanceof OWLDataAllValuesFrom) {
            OWLDataAllValuesFrom r = (OWLDataAllValuesFrom) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = dataRangeToString(r.getFiller());
            m.put("id", pIri + "_data_only_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "only"); m.put("fillerIri", fStr);
        } else if (ce instanceof OWLDataMinCardinality) {
            OWLDataMinCardinality r = (OWLDataMinCardinality) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = dataRangeToString(r.getFiller());
            m.put("id", pIri + "_data_min" + r.getCardinality() + "_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "min"); m.put("fillerIri", fStr); m.put("cardinality", r.getCardinality());
        } else if (ce instanceof OWLDataMaxCardinality) {
            OWLDataMaxCardinality r = (OWLDataMaxCardinality) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = dataRangeToString(r.getFiller());
            m.put("id", pIri + "_data_max" + r.getCardinality() + "_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "max"); m.put("fillerIri", fStr); m.put("cardinality", r.getCardinality());
        } else if (ce instanceof OWLDataExactCardinality) {
            OWLDataExactCardinality r = (OWLDataExactCardinality) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = dataRangeToString(r.getFiller());
            m.put("id", pIri + "_data_exactly" + r.getCardinality() + "_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "exactly"); m.put("fillerIri", fStr); m.put("cardinality", r.getCardinality());
        } else if (ce instanceof OWLDataHasValue) {
            OWLDataHasValue r = (OWLDataHasValue) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = r.getFiller().getLiteral();
            m.put("id", pIri + "_data_value_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "value"); m.put("fillerIri", fStr);
        } else {
            // Complex: intersection, union, complement, oneOf
            m.put("id", idPrefix); m.put("isRestriction", false); m.put("isComplex", true);
        }
        return m;
    }

    private String classExpressionToManchester(OWLOntology ont, OWLClassExpression ce) {
        if (!ce.isAnonymous()) {
            return getLabel(ont, ce.asOWLClass().getIRI());
        } else if (ce instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom r = (OWLObjectSomeValuesFrom) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " some " + classExpressionToManchester(ont, r.getFiller());
        } else if (ce instanceof OWLObjectAllValuesFrom) {
            OWLObjectAllValuesFrom r = (OWLObjectAllValuesFrom) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " only " + classExpressionToManchester(ont, r.getFiller());
        } else if (ce instanceof OWLObjectMinCardinality) {
            OWLObjectMinCardinality r = (OWLObjectMinCardinality) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " min " + r.getCardinality() + " " + classExpressionToManchester(ont, r.getFiller());
        } else if (ce instanceof OWLObjectMaxCardinality) {
            OWLObjectMaxCardinality r = (OWLObjectMaxCardinality) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " max " + r.getCardinality() + " " + classExpressionToManchester(ont, r.getFiller());
        } else if (ce instanceof OWLObjectExactCardinality) {
            OWLObjectExactCardinality r = (OWLObjectExactCardinality) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " exactly " + r.getCardinality() + " " + classExpressionToManchester(ont, r.getFiller());
        } else if (ce instanceof OWLObjectHasValue) {
            OWLObjectHasValue r = (OWLObjectHasValue) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            String ind = r.getFiller().isAnonymous() ? "?" : getLabel(ont, r.getFiller().asOWLNamedIndividual().getIRI());
            return p + " value " + ind;
        } else if (ce instanceof OWLObjectHasSelf) {
            OWLObjectHasSelf r = (OWLObjectHasSelf) ce;
            String p = r.getProperty().isAnonymous() ? "?" : getLabel(ont, r.getProperty().asOWLObjectProperty().getIRI());
            return p + " Self";
        } else if (ce instanceof OWLDataSomeValuesFrom) {
            OWLDataSomeValuesFrom r = (OWLDataSomeValuesFrom) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " some " + dataRangeToString(r.getFiller());
        } else if (ce instanceof OWLDataAllValuesFrom) {
            OWLDataAllValuesFrom r = (OWLDataAllValuesFrom) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " only " + dataRangeToString(r.getFiller());
        } else if (ce instanceof OWLDataMinCardinality) {
            OWLDataMinCardinality r = (OWLDataMinCardinality) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " min " + r.getCardinality() + " " + dataRangeToString(r.getFiller());
        } else if (ce instanceof OWLDataMaxCardinality) {
            OWLDataMaxCardinality r = (OWLDataMaxCardinality) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " max " + r.getCardinality() + " " + dataRangeToString(r.getFiller());
        } else if (ce instanceof OWLDataExactCardinality) {
            OWLDataExactCardinality r = (OWLDataExactCardinality) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " exactly " + r.getCardinality() + " " + dataRangeToString(r.getFiller());
        } else if (ce instanceof OWLDataHasValue) {
            OWLDataHasValue r = (OWLDataHasValue) ce;
            String p = (r.getProperty() instanceof OWLDataProperty) ? getLabel(ont, ((OWLDataProperty) r.getProperty()).getIRI()) : "?";
            return p + " value " + r.getFiller().getLiteral();
        } else if (ce instanceof OWLObjectIntersectionOf) {
            return ((OWLObjectIntersectionOf) ce).operands()
                .map(op -> classExpressionToManchester(ont, op))
                .collect(Collectors.joining(" and "));
        } else if (ce instanceof OWLObjectUnionOf) {
            return ((OWLObjectUnionOf) ce).operands()
                .map(op -> classExpressionToManchester(ont, op))
                .collect(Collectors.joining(" or "));
        } else if (ce instanceof OWLObjectComplementOf) {
            return "not " + classExpressionToManchester(ont, ((OWLObjectComplementOf) ce).getOperand());
        } else if (ce instanceof OWLObjectOneOf) {
            String inds = ((OWLObjectOneOf) ce).individuals()
                .map(i -> i.isAnonymous() ? "?" : getLabel(ont, i.asOWLNamedIndividual().getIRI()))
                .collect(Collectors.joining(", "));
            return "{" + inds + "}";
        }
        return ce.getClassExpressionType().getName();
    }

    private String getLabel(OWLOntology ont, IRI iri) {
        IRI rdfsLabelIri = IRI.create("http://www.w3.org/2000/01/rdf-schema#label");
        return ont.annotationAssertionAxioms(iri, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)
            .filter(ax -> ax.getProperty().getIRI().equals(rdfsLabelIri))
            .findFirst()
            .flatMap(ax -> ax.getValue().asLiteral())
            .map(OWLLiteral::getLiteral)
            .orElse(iri.getShortForm());
    }

    private String dataRangeToString(OWLDataRange range) {
        if (range instanceof OWLDatatype) {
            IRI dtIri = ((OWLDatatype) range).getIRI();
            String full = dtIri.toString();
            String xsdPrefix = "http://www.w3.org/2001/XMLSchema#";
            if (full.startsWith(xsdPrefix)) return "xsd:" + full.substring(xsdPrefix.length());
            return dtIri.getShortForm();
        }
        return range.getClass().getSimpleName();
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
