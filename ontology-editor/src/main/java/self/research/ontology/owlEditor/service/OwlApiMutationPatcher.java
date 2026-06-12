package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies a whitelist of simple {@link OntologyMutationService.MutationOp}s directly
 * to the in-memory OWLOntology so the fast-open path stays warm after edits.
 */
@Component
@Conditional(FastOpenCondition.class)
public class OwlApiMutationPatcher {

    private static final Logger log = LoggerFactory.getLogger(OwlApiMutationPatcher.class);

    private static final Set<String> PATCHABLE_TYPES = Set.of(
            "createClass",
            "addSubClassOf", "deleteSubClassOf", "updateSubClassOf",
            "addEquivalentClass", "deleteEquivalentClass", "updateEquivalentClass",
            "addDisjointWith", "deleteDisjointWith", "updateDisjointWith",
            "addAnnotation", "deleteAnnotation", "updateAnnotation", "updateClassLabel",
            "createIndividual", "deleteIndividual",
            "addClassAssertion", "removeClassAssertion",
            "addPropertyDomain", "deletePropertyDomain",
            "addPropertyRange", "deletePropertyRange",
            "addSubPropertyOf", "deleteSubPropertyOf",
            "deleteClass",
            "addObjectRestriction", "deleteObjectRestriction",
            "addPropertyChain", "deletePropertyChain"
    );

    private final ProjectOntologyCache ontologyCache;
    private final ConcurrentHashMap<String, Object> projectLocks = new ConcurrentHashMap<>();

    public OwlApiMutationPatcher(ProjectOntologyCache ontologyCache) {
        this.ontologyCache = ontologyCache;
    }

    public boolean tryPatch(String projectId, List<OntologyMutationService.MutationOp> ops) {
        if (ops == null || ops.isEmpty()) {
            return false;
        }
        if (ops.stream().anyMatch(op -> !PATCHABLE_TYPES.contains(op.type()))) {
            return false;
        }
        Optional<ProjectOntologyCache.CachedOntology> cached = ontologyCache.get(projectId);
        if (cached.isEmpty()) {
            return false;
        }

        Object lock = projectLocks.computeIfAbsent(projectId, id -> new Object());
        synchronized (lock) {
            Optional<ProjectOntologyCache.CachedOntology> again = ontologyCache.get(projectId);
            if (again.isEmpty()) {
                return false;
            }
            ProjectOntologyCache.CachedOntology entry = again.get();
            OWLOntology ontology = entry.ontology();
            OWLOntologyManager manager = entry.manager();
            OWLDataFactory df = manager.getOWLDataFactory();
            Set<OWLAxiom> toAdd = new HashSet<>();
            Set<OWLAxiom> toRemove = new HashSet<>();

            try {
                for (OntologyMutationService.MutationOp op : ops) {
                    if (!collectAxiomChanges(op, ontology, df, toAdd, toRemove)) {
                        return false;
                    }
                }
                if (!toRemove.isEmpty()) {
                    manager.removeAxioms(ontology, toRemove);
                }
                if (!toAdd.isEmpty()) {
                    manager.addAxioms(ontology, toAdd);
                }
                log.info("[OwlApiPatch] Patched {} remove + {} add axioms for project {}",
                        toRemove.size(), toAdd.size(), projectId);
                return true;
            } catch (Exception e) {
                log.warn("[OwlApiPatch] Patch failed for project {}: {}", projectId, e.getMessage());
                return false;
            }
        }
    }

    private boolean collectAxiomChanges(OntologyMutationService.MutationOp op,
                                        OWLOntology ontology,
                                        OWLDataFactory df,
                                        Set<OWLAxiom> toAdd,
                                        Set<OWLAxiom> toRemove) {
        return switch (op.type()) {
            case "addSubClassOf" -> {
                Optional<OWLClass> sub = namedClass(op.iri(), df);
                Optional<OWLClass> sup = namedClass(op.target(), df);
                if (sub.isEmpty() || sup.isEmpty()) yield false;
                toAdd.add(df.getOWLSubClassOfAxiom(sub.get(), sup.get()));
                yield true;
            }
            case "deleteSubClassOf" -> {
                Optional<OWLClass> sub = namedClass(op.iri(), df);
                if (sub.isEmpty() || op.target() == null) yield false;
                if (op.target().startsWith("_:")) yield false;
                Optional<OWLClass> sup = namedClass(op.target(), df);
                if (sup.isEmpty()) yield false;
                toRemove.add(df.getOWLSubClassOfAxiom(sub.get(), sup.get()));
                yield true;
            }
            case "updateSubClassOf" -> {
                Optional<OWLClass> sub = namedClass(op.iri(), df);
                Optional<OWLClass> oldSup = namedClass(op.value(), df);
                Optional<OWLClass> newSup = namedClass(op.target(), df);
                if (sub.isEmpty() || oldSup.isEmpty() || newSup.isEmpty()) yield false;
                toRemove.add(df.getOWLSubClassOfAxiom(sub.get(), oldSup.get()));
                toAdd.add(df.getOWLSubClassOfAxiom(sub.get(), newSup.get()));
                yield true;
            }
            case "addEquivalentClass" -> {
                Optional<OWLClass> a = namedClass(op.iri(), df);
                Optional<OWLClass> b = namedClass(op.target(), df);
                if (a.isEmpty() || b.isEmpty()) yield false;
                toAdd.add(df.getOWLEquivalentClassesAxiom(a.get(), b.get()));
                yield true;
            }
            case "deleteEquivalentClass" -> {
                Optional<OWLClass> a = namedClass(op.iri(), df);
                if (a.isEmpty() || op.target() == null || op.target().startsWith("_:")) yield false;
                Optional<OWLClass> b = namedClass(op.target(), df);
                if (b.isEmpty()) yield false;
                toRemove.add(df.getOWLEquivalentClassesAxiom(a.get(), b.get()));
                yield true;
            }
            case "updateEquivalentClass" -> {
                Optional<OWLClass> sub = namedClass(op.iri(), df);
                Optional<OWLClass> oldEq = namedClass(op.value(), df);
                Optional<OWLClass> newEq = namedClass(op.target(), df);
                if (sub.isEmpty() || oldEq.isEmpty() || newEq.isEmpty()) yield false;
                toRemove.add(df.getOWLEquivalentClassesAxiom(sub.get(), oldEq.get()));
                toAdd.add(df.getOWLEquivalentClassesAxiom(sub.get(), newEq.get()));
                yield true;
            }
            case "addDisjointWith" -> {
                Optional<OWLClass> a = namedClass(op.iri(), df);
                Optional<OWLClass> b = namedClass(op.target(), df);
                if (a.isEmpty() || b.isEmpty()) yield false;
                toAdd.add(df.getOWLDisjointClassesAxiom(a.get(), b.get()));
                yield true;
            }
            case "deleteDisjointWith" -> {
                Optional<OWLClass> a = namedClass(op.iri(), df);
                if (a.isEmpty() || op.target() == null || op.target().startsWith("_:")) yield false;
                Optional<OWLClass> b = namedClass(op.target(), df);
                if (b.isEmpty()) yield false;
                toRemove.add(df.getOWLDisjointClassesAxiom(a.get(), b.get()));
                yield true;
            }
            case "updateDisjointWith" -> {
                Optional<OWLClass> sub = namedClass(op.iri(), df);
                Optional<OWLClass> oldD = namedClass(op.value(), df);
                Optional<OWLClass> newD = namedClass(op.target(), df);
                if (sub.isEmpty() || oldD.isEmpty() || newD.isEmpty()) yield false;
                toRemove.add(df.getOWLDisjointClassesAxiom(sub.get(), oldD.get()));
                toAdd.add(df.getOWLDisjointClassesAxiom(sub.get(), newD.get()));
                yield true;
            }
            case "addAnnotation" -> {
                if (op.iri() == null || op.property() == null || op.value() == null) yield false;
                toAdd.add(df.getOWLAnnotationAssertionAxiom(
                        df.getOWLAnnotationProperty(IRI.create(op.property())),
                        IRI.create(op.iri()),
                        literalValue(df, op.value(), op.language(), op.datatype())));
                yield true;
            }
            case "updateClassLabel" -> {
                if (op.iri() == null || op.label() == null) yield false;
                IRI subject = IRI.create(op.iri());
                ontology.annotationAssertionAxioms(subject).forEach(ax -> {
                    if (ax.getProperty().equals(df.getRDFSLabel())) {
                        toRemove.add(ax);
                    }
                });
                toAdd.add(df.getOWLAnnotationAssertionAxiom(
                        df.getRDFSLabel(), subject, df.getOWLLiteral(op.label())));
                yield true;
            }
            case "deleteAnnotation" -> {
                if (op.iri() == null || op.property() == null || op.value() == null) yield false;
                toRemove.add(df.getOWLAnnotationAssertionAxiom(
                        df.getOWLAnnotationProperty(IRI.create(op.property())),
                        IRI.create(op.iri()),
                        literalValue(df, op.value(), op.language(), op.datatype())));
                yield true;
            }
            case "updateAnnotation" -> {
                if (op.iri() == null || op.property() == null || op.value() == null || op.oldValue() == null) {
                    yield false;
                }
                IRI subject = IRI.create(op.iri());
                OWLAnnotationProperty prop = df.getOWLAnnotationProperty(IRI.create(op.property()));
                toRemove.add(df.getOWLAnnotationAssertionAxiom(prop, subject,
                        literalValue(df, op.oldValue(), op.language(), op.datatype())));
                toAdd.add(df.getOWLAnnotationAssertionAxiom(prop, subject,
                        literalValue(df, op.value(), op.language(), op.datatype())));
                yield true;
            }
            case "createClass" -> {
                Optional<OWLClass> cls = namedClass(op.iri(), df);
                if (cls.isEmpty()) yield false;
                toAdd.add(df.getOWLDeclarationAxiom(cls.get()));
                if (op.label() != null && !op.label().isBlank()) {
                    toAdd.add(df.getOWLAnnotationAssertionAxiom(
                            df.getRDFSLabel(), cls.get().getIRI(), df.getOWLLiteral(op.label())));
                }
                if (op.parent() != null && !op.parent().isBlank()) {
                    Optional<OWLClass> parent = namedClass(op.parent(), df);
                    if (parent.isEmpty()) yield false;
                    toAdd.add(df.getOWLSubClassOfAxiom(cls.get(), parent.get()));
                }
                yield true;
            }
            case "createIndividual" -> {
                if (op.iri() == null || op.classIri() == null) yield false;
                OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(op.iri()));
                toAdd.add(df.getOWLClassAssertionAxiom(df.getOWLClass(IRI.create(op.classIri())), ind));
                toAdd.add(df.getOWLDeclarationAxiom(ind));
                if (op.label() != null && !op.label().isBlank()) {
                    toAdd.add(df.getOWLAnnotationAssertionAxiom(
                            df.getRDFSLabel(), ind.getIRI(), df.getOWLLiteral(op.label())));
                }
                yield true;
            }
            case "deleteIndividual" -> {
                if (op.iri() == null) yield false;
                OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(op.iri()));
                toRemove.addAll(ontology.getAxioms(ind));
                yield true;
            }
            case "addClassAssertion" -> {
                Optional<OWLClass> cls = namedClass(op.classIri(), df);
                if (cls.isEmpty() || op.iri() == null) yield false;
                OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(op.iri()));
                toAdd.add(df.getOWLClassAssertionAxiom(cls.get(), ind));
                yield true;
            }
            case "removeClassAssertion" -> {
                Optional<OWLClass> cls = namedClass(op.classIri(), df);
                if (cls.isEmpty() || op.iri() == null) yield false;
                OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(op.iri()));
                toRemove.add(df.getOWLClassAssertionAxiom(cls.get(), ind));
                yield true;
            }
            case "addPropertyDomain" -> {
                if (op.restrictionType() != null) yield false;
                yield addPropertyDomain(ontology, df, op.iri(), op.target(), toAdd);
            }
            case "deletePropertyDomain" -> {
                if (op.restrictionType() != null || op.target() == null || op.target().contains("|||")) {
                    yield false;
                }
                yield removePropertyDomain(ontology, df, op.iri(), op.target(), toRemove);
            }
            case "addPropertyRange" -> {
                if (op.restrictionType() != null) yield false;
                if (op.target() != null && op.target().contains("[")) yield false;
                yield addPropertyRange(ontology, df, op.iri(), op.target(), toAdd);
            }
            case "deletePropertyRange" -> {
                if (op.restrictionType() != null || op.target() == null) yield false;
                if (op.target().contains("|||") || op.target().contains("[")) yield false;
                yield removePropertyRange(ontology, df, op.iri(), op.target(), toRemove);
            }
            case "addSubPropertyOf" -> {
                yield addSubPropertyOf(df, op.iri(), op.target(), toAdd);
            }
            case "deleteSubPropertyOf" -> {
                yield removeSubPropertyOf(df, op.iri(), op.target(), toRemove);
            }
            case "deleteClass" -> {
                Optional<OWLClass> cls = namedClass(op.iri(), df);
                if (cls.isEmpty()) yield false;
                toRemove.addAll(ontology.getAxioms(cls.get()));
                yield true;
            }
            case "addObjectRestriction" -> {
                yield addObjectRestriction(df, op, toAdd);
            }
            case "deleteObjectRestriction" -> {
                yield removeObjectRestriction(df, op, toRemove);
            }
            case "addPropertyChain" -> {
                yield addPropertyChain(df, op, toAdd);
            }
            case "deletePropertyChain" -> {
                yield removePropertyChain(df, op, toRemove);
            }
            default -> false;
        };
    }

    private boolean addObjectRestriction(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        Optional<OWLClass> subject = namedClass(op.iri(), df);
        Optional<OWLClass> filler = namedClass(op.target(), df);
        if (subject.isEmpty() || filler.isEmpty() || op.property() == null || op.restrictionType() == null) {
            return false;
        }
        if (op.cardinality() != null) {
            return false;
        }
        OWLObjectPropertyExpression prop = df.getOWLObjectProperty(IRI.create(op.property()));
        OWLClassExpression restriction = buildNamedObjectRestriction(df, prop, op.restrictionType(), filler.get());
        if (restriction == null) {
            return false;
        }
        OWLAxiom axiom = classExpressionAxiom(df, subject.get(), restriction, op.axiomType());
        if (axiom == null) {
            return false;
        }
        toAdd.add(axiom);
        return true;
    }

    private boolean removeObjectRestriction(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toRemove) {
        Optional<OWLClass> subject = namedClass(op.iri(), df);
        Optional<OWLClass> filler = namedClass(op.target(), df);
        if (subject.isEmpty() || filler.isEmpty() || op.property() == null || op.restrictionType() == null) {
            return false;
        }
        if (op.cardinality() != null) {
            return false;
        }
        OWLObjectPropertyExpression prop = df.getOWLObjectProperty(IRI.create(op.property()));
        OWLClassExpression restriction = buildNamedObjectRestriction(df, prop, op.restrictionType(), filler.get());
        if (restriction == null) {
            return false;
        }
        OWLAxiom axiom = classExpressionAxiom(df, subject.get(), restriction, op.axiomType());
        if (axiom == null) {
            return false;
        }
        toRemove.add(axiom);
        return true;
    }

    private OWLClassExpression buildNamedObjectRestriction(OWLDataFactory df,
                                                             OWLObjectPropertyExpression property,
                                                             String restrictionType,
                                                             OWLClass filler) {
        return switch (restrictionType) {
            case "some" -> df.getOWLObjectSomeValuesFrom(property, filler);
            case "only" -> df.getOWLObjectAllValuesFrom(property, filler);
            default -> null;
        };
    }

    private OWLAxiom classExpressionAxiom(OWLDataFactory df,
                                          OWLClass subject,
                                          OWLClassExpression expression,
                                          String axiomType) {
        if (axiomType == null || "SubClassOf".equals(axiomType)) {
            return df.getOWLSubClassOfAxiom(subject, expression);
        }
        if ("EquivalentTo".equals(axiomType)) {
            return df.getOWLEquivalentClassesAxiom(subject, expression);
        }
        if ("DisjointWith".equals(axiomType)) {
            return df.getOWLDisjointClassesAxiom(subject, expression);
        }
        return null;
    }

    private boolean addPropertyChain(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null || op.value() == null || op.value().isBlank()) {
            return false;
        }
        List<OWLObjectProperty> chain = parsePropertyChain(df, op.value());
        if (chain == null || chain.size() < 2) {
            return false;
        }
        toAdd.add(df.getOWLSubPropertyChainOfAxiom(chain, df.getOWLObjectProperty(IRI.create(op.iri()))));
        return true;
    }

    private boolean removePropertyChain(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toRemove) {
        if (op.iri() == null || op.value() == null || op.value().isBlank()) {
            return false;
        }
        List<OWLObjectProperty> chain = parsePropertyChain(df, op.value());
        if (chain == null || chain.size() < 2) {
            return false;
        }
        toRemove.add(df.getOWLSubPropertyChainOfAxiom(chain, df.getOWLObjectProperty(IRI.create(op.iri()))));
        return true;
    }

    private List<OWLObjectProperty> parsePropertyChain(OWLDataFactory df, String chainValue) {
        String[] parts = chainValue.split(" o ");
        List<OWLObjectProperty> chain = new ArrayList<>();
        for (String part : parts) {
            String iri = part == null ? "" : part.trim();
            if (iri.isBlank() || iri.startsWith("_:")) {
                return null;
            }
            chain.add(df.getOWLObjectProperty(IRI.create(iri)));
        }
        return chain;
    }

    private boolean addPropertyDomain(OWLOntology ontology,
                                      OWLDataFactory df,
                                      String propertyIri,
                                      String domainIri,
                                      Set<OWLAxiom> toAdd) {
        Optional<OWLClass> domain = namedClass(domainIri, df);
        if (domain.isEmpty() || propertyIri == null) {
            return false;
        }
        IRI propIri = IRI.create(propertyIri);
        if (ontology.containsDataPropertyInSignature(propIri, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)) {
            toAdd.add(df.getOWLDataPropertyDomainAxiom(df.getOWLDataProperty(propIri), domain.get()));
            return true;
        }
        toAdd.add(df.getOWLObjectPropertyDomainAxiom(df.getOWLObjectProperty(propIri), domain.get()));
        return true;
    }

    private boolean removePropertyDomain(OWLOntology ontology,
                                         OWLDataFactory df,
                                         String propertyIri,
                                         String domainIri,
                                         Set<OWLAxiom> toRemove) {
        Optional<OWLClass> domain = namedClass(domainIri, df);
        if (domain.isEmpty() || propertyIri == null) {
            return false;
        }
        IRI propIri = IRI.create(propertyIri);
        if (ontology.containsDataPropertyInSignature(propIri, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)) {
            toRemove.add(df.getOWLDataPropertyDomainAxiom(df.getOWLDataProperty(propIri), domain.get()));
            return true;
        }
        toRemove.add(df.getOWLObjectPropertyDomainAxiom(df.getOWLObjectProperty(propIri), domain.get()));
        return true;
    }

    private boolean addPropertyRange(OWLOntology ontology,
                                     OWLDataFactory df,
                                     String propertyIri,
                                     String rangeIri,
                                     Set<OWLAxiom> toAdd) {
        if (propertyIri == null || rangeIri == null || rangeIri.isBlank()) {
            return false;
        }
        IRI propIri = IRI.create(propertyIri);
        if (ontology.containsDataPropertyInSignature(propIri, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)
                || rangeIri.contains("XMLSchema#") || rangeIri.startsWith("xsd:")) {
            String resolved = rangeIri.startsWith("xsd:") ? "http://www.w3.org/2001/XMLSchema#" + rangeIri.substring(4) : rangeIri;
            toAdd.add(df.getOWLDataPropertyRangeAxiom(df.getOWLDataProperty(propIri), df.getOWLDatatype(IRI.create(resolved))));
            return true;
        }
        Optional<OWLClass> range = namedClass(rangeIri, df);
        if (range.isEmpty()) {
            return false;
        }
        toAdd.add(df.getOWLObjectPropertyRangeAxiom(df.getOWLObjectProperty(propIri), range.get()));
        return true;
    }

    private boolean removePropertyRange(OWLOntology ontology,
                                          OWLDataFactory df,
                                          String propertyIri,
                                          String rangeIri,
                                          Set<OWLAxiom> toRemove) {
        if (propertyIri == null || rangeIri == null || rangeIri.isBlank()) {
            return false;
        }
        IRI propIri = IRI.create(propertyIri);
        if (ontology.containsDataPropertyInSignature(propIri, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)
                || rangeIri.contains("XMLSchema#") || rangeIri.startsWith("xsd:")) {
            String resolved = rangeIri.startsWith("xsd:") ? "http://www.w3.org/2001/XMLSchema#" + rangeIri.substring(4) : rangeIri;
            toRemove.add(df.getOWLDataPropertyRangeAxiom(df.getOWLDataProperty(propIri), df.getOWLDatatype(IRI.create(resolved))));
            return true;
        }
        Optional<OWLClass> range = namedClass(rangeIri, df);
        if (range.isEmpty()) {
            return false;
        }
        toRemove.add(df.getOWLObjectPropertyRangeAxiom(df.getOWLObjectProperty(propIri), range.get()));
        return true;
    }

    private boolean addSubPropertyOf(OWLDataFactory df, String subIri, String superIri, Set<OWLAxiom> toAdd) {
        if (subIri == null || superIri == null) {
            return false;
        }
        if (superIri.contains("XMLSchema#") || subIri.contains("XMLSchema#")) {
            return false;
        }
        toAdd.add(df.getOWLSubObjectPropertyOfAxiom(
                df.getOWLObjectProperty(IRI.create(subIri)),
                df.getOWLObjectProperty(IRI.create(superIri))));
        return true;
    }

    private boolean removeSubPropertyOf(OWLDataFactory df, String subIri, String superIri, Set<OWLAxiom> toRemove) {
        if (subIri == null || superIri == null) {
            return false;
        }
        toRemove.add(df.getOWLSubObjectPropertyOfAxiom(
                df.getOWLObjectProperty(IRI.create(subIri)),
                df.getOWLObjectProperty(IRI.create(superIri))));
        return true;
    }

    private Optional<OWLClass> namedClass(String iri, OWLDataFactory df) {
        if (iri == null || iri.isBlank() || iri.startsWith("_:")) {
            return Optional.empty();
        }
        return Optional.of(df.getOWLClass(IRI.create(iri)));
    }

    private OWLAnnotationValue literalValue(OWLDataFactory df, String value, String lang, String datatype) {
        if (datatype != null && !datatype.isBlank()) {
            return df.getOWLLiteral(value, df.getOWLDatatype(IRI.create(datatype)));
        }
        if (lang != null && !lang.isBlank()) {
            return df.getOWLLiteral(value, lang);
        }
        return df.getOWLLiteral(value);
    }
}
