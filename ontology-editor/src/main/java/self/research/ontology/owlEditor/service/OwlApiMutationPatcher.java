package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import org.semanticweb.owlapi.model.parameters.Imports;
import self.research.ontology.owlEditor.service.owlapi.OwlApiQuerySupport;

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
            "addSubClassOf", "deleteSubClassOf", "updateSubClassOf", "deleteAxiom",
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
            "addDataRestriction", "deleteDataRestriction",
            "addPropertyChain", "deletePropertyChain",
            "createObjectProperty", "createDataProperty", "createAnnotationProperty",
            "deleteObjectProperty", "deleteDataProperty", "deleteAnnotationProperty",
            "addInverseProperty", "deleteInverseProperty",
            "addEquivalentProperty", "deleteEquivalentProperty",
            "addDisjointProperty", "deleteDisjointProperty",
            "addCharacteristic", "deleteCharacteristic",
            "addObjectPropertyAssertion", "deleteObjectPropertyAssertion",
            "addDataPropertyAssertion", "deleteDataPropertyAssertion",
            "addSameIndividual", "deleteSameIndividual",
            "addDifferentIndividual", "deleteDifferentIndividual",
            "createDatatype", "deleteDatatype"
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
                Optional<OWLClass> sup = namedClass(op.target(), df);
                if (sup.isPresent() && ontology.containsClassInSignature(sup.get().getIRI(), Imports.INCLUDED)) {
                    toRemove.add(df.getOWLSubClassOfAxiom(sub.get(), sup.get()));
                    yield true;
                }
                // op.target() isn't a declared class — desktop has no stable id for the complex/
                // anonymous superclass expression this row actually represents (its "id" is
                // either a synthetic per-request index or a bare Fuseki blank-node string that
                // means nothing in this separately-parsed in-memory model). Match by the same
                // Manchester text the UI showed for this row instead.
                Optional<OWLSubClassOfAxiom> match = findAnonymousSubClassOfAxiom(ontology, sub.get(), op.value());
                if (match.isEmpty()) yield false;
                toRemove.add(match.get());
                yield true;
            }
            case "deleteAxiom" -> {
                // Covers two distinct shapes the frontend both route through this same generic
                // op (see ClassEditor.tsx's handleDeleteGCA, shared by the "General class axioms"
                // and "SubClass Of (Anonymous Ancestor)" sections):
                //  - GCI: anonymous-SUBJECT SubClassOf, ancestorIri = the superclass.
                //  - Anonymous ancestor: named-subject (ancestorIri) SubClassOf anonymous
                //    superclass — same shape as the main "Sub Class Of" table's complex entries.
                // Neither has a stable id on desktop (separately-parsed in-memory model), so
                // match by the definition text rendered for this row instead.
                if (op.ancestorIri() == null) yield false;
                Optional<OWLClass> anchor = namedClass(op.ancestorIri(), df);
                if (anchor.isEmpty()) yield false;
                Optional<OWLSubClassOfAxiom> gci = findGciAxiom(ontology, anchor.get(), op.value());
                if (gci.isPresent()) {
                    toRemove.add(gci.get());
                    yield true;
                }
                Optional<OWLSubClassOfAxiom> ancestor = findAnonymousSubClassOfAxiom(ontology, anchor.get(), op.value());
                if (ancestor.isEmpty()) yield false;
                toRemove.add(ancestor.get());
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
                if (a.isEmpty() || op.target() == null) yield false;
                Optional<OWLClass> b = namedClass(op.target(), df);
                if (b.isPresent() && ontology.containsClassInSignature(b.get().getIRI(), Imports.INCLUDED)) {
                    toRemove.add(df.getOWLEquivalentClassesAxiom(a.get(), b.get()));
                    yield true;
                }
                // Same reasoning as deleteSubClassOf: op.target() isn't a declared class, so this
                // is a complex/anonymous equivalent-class expression — match by definition text.
                Optional<OWLEquivalentClassesAxiom> match = findAnonymousEquivalentClassAxiom(ontology, a.get(), op.value());
                if (match.isEmpty()) yield false;
                toRemove.add(match.get());
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
                toRemove.addAll(ontology.getReferencingAxioms(ind));
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
                yield addSubPropertyOf(ontology, df, op.iri(), op.target(), toAdd);
            }
            case "deleteSubPropertyOf" -> {
                yield removeSubPropertyOf(ontology, df, op.iri(), op.target(), toRemove);
            }
            case "deleteClass" -> {
                Optional<OWLClass> cls = namedClass(op.iri(), df);
                if (cls.isEmpty()) yield false;
                toRemove.addAll(ontology.getReferencingAxioms(cls.get()));
                yield true;
            }
            case "addObjectRestriction" -> {
                yield addObjectRestriction(df, op, toAdd);
            }
            case "deleteObjectRestriction" -> {
                yield removeObjectRestriction(df, op, toRemove);
            }
            case "addDataRestriction" -> {
                yield addDataRestriction(df, op, toAdd);
            }
            case "deleteDataRestriction" -> {
                yield removeDataRestriction(df, op, toRemove);
            }
            case "addPropertyChain" -> {
                yield addPropertyChain(df, op, toAdd);
            }
            case "deletePropertyChain" -> {
                yield removePropertyChain(df, op, toRemove);
            }
            case "createObjectProperty" -> {
                yield createObjectProperty(df, op, toAdd);
            }
            case "createDataProperty" -> {
                yield createDataProperty(df, op, toAdd);
            }
            case "createAnnotationProperty" -> {
                yield createAnnotationProperty(df, op, toAdd);
            }
            case "deleteObjectProperty" -> {
                yield deleteObjectProperty(ontology, df, op.iri(), toRemove);
            }
            case "deleteDataProperty" -> {
                yield deleteDataProperty(ontology, df, op.iri(), toRemove);
            }
            case "deleteAnnotationProperty" -> {
                yield deleteAnnotationProperty(ontology, df, op.iri(), toRemove);
            }
            case "addInverseProperty" -> {
                yield addInverseProperty(df, op, toAdd);
            }
            case "deleteInverseProperty" -> {
                yield removeInverseProperty(df, op, toRemove);
            }
            case "addEquivalentProperty" -> {
                yield addEquivalentProperty(df, op, toAdd);
            }
            case "deleteEquivalentProperty" -> {
                yield removeEquivalentProperty(df, op, toRemove);
            }
            case "addDisjointProperty" -> {
                yield addDisjointProperty(df, op, toAdd);
            }
            case "deleteDisjointProperty" -> {
                yield removeDisjointProperty(df, op, toRemove);
            }
            case "addCharacteristic" -> {
                yield addCharacteristic(df, op, toAdd);
            }
            case "deleteCharacteristic" -> {
                yield removeCharacteristic(df, op, toRemove);
            }
            case "addObjectPropertyAssertion" -> {
                yield addObjectPropertyAssertion(df, op, toAdd);
            }
            case "deleteObjectPropertyAssertion" -> {
                yield removeObjectPropertyAssertion(df, op, toRemove);
            }
            case "addDataPropertyAssertion" -> {
                yield addDataPropertyAssertion(df, op, toAdd);
            }
            case "deleteDataPropertyAssertion" -> {
                yield removeDataPropertyAssertion(df, op, toRemove);
            }
            case "addSameIndividual" -> {
                yield addSameIndividual(df, op, toAdd);
            }
            case "deleteSameIndividual" -> {
                yield removeSameIndividual(df, op, toRemove);
            }
            case "addDifferentIndividual" -> {
                yield addDifferentIndividual(df, op, toAdd);
            }
            case "deleteDifferentIndividual" -> {
                yield removeDifferentIndividual(df, op, toRemove);
            }
            case "createDatatype" -> {
                yield createDatatype(df, op, toAdd);
            }
            case "deleteDatatype" -> {
                if (op.iri() == null) yield false;
                OWLDatatype dt = df.getOWLDatatype(IRI.create(op.iri()));
                toRemove.addAll(ontology.getReferencingAxioms(dt));
                yield true;
            }
            default -> false;
        };
    }

    private boolean createObjectProperty(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null) return false;
        OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(op.iri()));
        toAdd.add(df.getOWLDeclarationAxiom(prop));
        if (op.label() != null && !op.label().isBlank()) {
            toAdd.add(df.getOWLAnnotationAssertionAxiom(
                    df.getRDFSLabel(), prop.getIRI(), df.getOWLLiteral(op.label())));
        }
        if (hasRealPropertyParent(op.parent())) {
            toAdd.add(df.getOWLSubObjectPropertyOfAxiom(prop, df.getOWLObjectProperty(IRI.create(op.parent()))));
        }
        return true;
    }

    private boolean createDataProperty(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null) return false;
        OWLDataProperty prop = df.getOWLDataProperty(IRI.create(op.iri()));
        toAdd.add(df.getOWLDeclarationAxiom(prop));
        if (op.label() != null && !op.label().isBlank()) {
            toAdd.add(df.getOWLAnnotationAssertionAxiom(
                    df.getRDFSLabel(), prop.getIRI(), df.getOWLLiteral(op.label())));
        }
        if (hasRealPropertyParent(op.parent())) {
            toAdd.add(df.getOWLSubDataPropertyOfAxiom(prop, df.getOWLDataProperty(IRI.create(op.parent()))));
        }
        return true;
    }

    private boolean hasRealPropertyParent(String parent) {
        return parent != null && !parent.isBlank()
                && !parent.contains("topObjectProperty")
                && !parent.contains("topDataProperty");
    }

    private boolean createAnnotationProperty(OWLDataFactory df,
                                             OntologyMutationService.MutationOp op,
                                             Set<OWLAxiom> toAdd) {
        if (op.iri() == null) return false;
        OWLAnnotationProperty prop = df.getOWLAnnotationProperty(IRI.create(op.iri()));
        toAdd.add(df.getOWLDeclarationAxiom(prop));
        if (op.label() != null && !op.label().isBlank()) {
            toAdd.add(df.getOWLAnnotationAssertionAxiom(
                    df.getRDFSLabel(), prop.getIRI(), df.getOWLLiteral(op.label())));
        }
        if (hasRealPropertyParent(op.parent())) {
            toAdd.add(df.getOWLSubAnnotationPropertyOfAxiom(prop, df.getOWLAnnotationProperty(IRI.create(op.parent()))));
        }
        return true;
    }

    private boolean deleteObjectProperty(OWLOntology ontology, OWLDataFactory df, String iri, Set<OWLAxiom> toRemove) {
        if (iri == null) return false;
        toRemove.addAll(ontology.getReferencingAxioms(df.getOWLObjectProperty(IRI.create(iri))));
        return true;
    }

    private boolean deleteDataProperty(OWLOntology ontology, OWLDataFactory df, String iri, Set<OWLAxiom> toRemove) {
        if (iri == null) return false;
        toRemove.addAll(ontology.getReferencingAxioms(df.getOWLDataProperty(IRI.create(iri))));
        return true;
    }

    private boolean deleteAnnotationProperty(OWLOntology ontology, OWLDataFactory df, String iri, Set<OWLAxiom> toRemove) {
        if (iri == null) return false;
        toRemove.addAll(ontology.getReferencingAxioms(df.getOWLAnnotationProperty(IRI.create(iri))));
        return true;
    }

    private boolean addInverseProperty(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null || op.target() == null) return false;
        OWLObjectProperty a = df.getOWLObjectProperty(IRI.create(op.iri()));
        OWLObjectProperty b = df.getOWLObjectProperty(IRI.create(op.target()));
        toAdd.add(df.getOWLInverseObjectPropertiesAxiom(a, b));
        return true;
    }

    private boolean removeInverseProperty(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toRemove) {
        if (op.iri() == null || op.target() == null) return false;
        OWLObjectProperty a = df.getOWLObjectProperty(IRI.create(op.iri()));
        OWLObjectProperty b = df.getOWLObjectProperty(IRI.create(op.target()));
        toRemove.add(df.getOWLInverseObjectPropertiesAxiom(a, b));
        return true;
    }

    private boolean addEquivalentProperty(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null || op.target() == null) return false;
        toAdd.add(df.getOWLEquivalentObjectPropertiesAxiom(
                df.getOWLObjectProperty(IRI.create(op.iri())),
                df.getOWLObjectProperty(IRI.create(op.target()))));
        return true;
    }

    private boolean removeEquivalentProperty(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toRemove) {
        if (op.iri() == null || op.target() == null) return false;
        toRemove.add(df.getOWLEquivalentObjectPropertiesAxiom(
                df.getOWLObjectProperty(IRI.create(op.iri())),
                df.getOWLObjectProperty(IRI.create(op.target()))));
        return true;
    }

    private boolean addDisjointProperty(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null || op.target() == null) return false;
        toAdd.add(df.getOWLDisjointObjectPropertiesAxiom(
                df.getOWLObjectProperty(IRI.create(op.iri())),
                df.getOWLObjectProperty(IRI.create(op.target()))));
        return true;
    }

    private boolean removeDisjointProperty(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toRemove) {
        if (op.iri() == null || op.target() == null) return false;
        toRemove.add(df.getOWLDisjointObjectPropertiesAxiom(
                df.getOWLObjectProperty(IRI.create(op.iri())),
                df.getOWLObjectProperty(IRI.create(op.target()))));
        return true;
    }

    private boolean addCharacteristic(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null || op.target() == null) return false;
        OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(op.iri()));
        OWLAxiom axiom = characteristicAxiom(df, prop, op.target());
        if (axiom == null) return false;
        toAdd.add(axiom);
        return true;
    }

    private boolean removeCharacteristic(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toRemove) {
        if (op.iri() == null || op.target() == null) return false;
        OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(op.iri()));
        OWLAxiom axiom = characteristicAxiom(df, prop, op.target());
        if (axiom == null) return false;
        toRemove.add(axiom);
        return true;
    }

    private OWLAxiom characteristicAxiom(OWLDataFactory df, OWLObjectProperty prop, String characteristicIri) {
        if (characteristicIri == null) return null;
        return switch (characteristicIri) {
            case "http://www.w3.org/2002/07/owl#FunctionalProperty",
                 "owl:FunctionalProperty" -> df.getOWLFunctionalObjectPropertyAxiom(prop);
            case "http://www.w3.org/2002/07/owl#InverseFunctionalProperty",
                 "owl:InverseFunctionalProperty" -> df.getOWLInverseFunctionalObjectPropertyAxiom(prop);
            case "http://www.w3.org/2002/07/owl#TransitiveProperty",
                 "owl:TransitiveProperty" -> df.getOWLTransitiveObjectPropertyAxiom(prop);
            case "http://www.w3.org/2002/07/owl#SymmetricProperty",
                 "owl:SymmetricProperty" -> df.getOWLSymmetricObjectPropertyAxiom(prop);
            case "http://www.w3.org/2002/07/owl#AsymmetricProperty",
                 "owl:AsymmetricProperty" -> df.getOWLAsymmetricObjectPropertyAxiom(prop);
            case "http://www.w3.org/2002/07/owl#ReflexiveProperty",
                 "owl:ReflexiveProperty" -> df.getOWLReflexiveObjectPropertyAxiom(prop);
            case "http://www.w3.org/2002/07/owl#IrreflexiveProperty",
                 "owl:IrreflexiveProperty" -> df.getOWLIrreflexiveObjectPropertyAxiom(prop);
            default -> null;
        };
    }

    private boolean addObjectPropertyAssertion(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null || op.property() == null || op.target() == null) return false;
        toAdd.add(df.getOWLObjectPropertyAssertionAxiom(
                df.getOWLObjectProperty(IRI.create(op.property())),
                df.getOWLNamedIndividual(IRI.create(op.iri())),
                df.getOWLNamedIndividual(IRI.create(op.target()))));
        return true;
    }

    private boolean removeObjectPropertyAssertion(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toRemove) {
        if (op.iri() == null || op.property() == null || op.target() == null) return false;
        toRemove.add(df.getOWLObjectPropertyAssertionAxiom(
                df.getOWLObjectProperty(IRI.create(op.property())),
                df.getOWLNamedIndividual(IRI.create(op.iri())),
                df.getOWLNamedIndividual(IRI.create(op.target()))));
        return true;
    }

    private boolean addDataPropertyAssertion(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null || op.property() == null || op.value() == null) return false;
        OWLLiteral literal = dataLiteral(df, op.value(), op.language(), op.datatype());
        toAdd.add(df.getOWLDataPropertyAssertionAxiom(
                df.getOWLDataProperty(IRI.create(op.property())),
                df.getOWLNamedIndividual(IRI.create(op.iri())),
                literal));
        return true;
    }

    private boolean removeDataPropertyAssertion(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toRemove) {
        if (op.iri() == null || op.property() == null || op.value() == null) return false;
        OWLLiteral literal = dataLiteral(df, op.value(), op.language(), op.datatype());
        toRemove.add(df.getOWLDataPropertyAssertionAxiom(
                df.getOWLDataProperty(IRI.create(op.property())),
                df.getOWLNamedIndividual(IRI.create(op.iri())),
                literal));
        return true;
    }

    private boolean addSameIndividual(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null || op.target() == null) return false;
        toAdd.add(df.getOWLSameIndividualAxiom(
                df.getOWLNamedIndividual(IRI.create(op.iri())),
                df.getOWLNamedIndividual(IRI.create(op.target()))));
        return true;
    }

    private boolean removeSameIndividual(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toRemove) {
        if (op.iri() == null || op.target() == null) return false;
        toRemove.add(df.getOWLSameIndividualAxiom(
                df.getOWLNamedIndividual(IRI.create(op.iri())),
                df.getOWLNamedIndividual(IRI.create(op.target()))));
        return true;
    }

    private boolean addDifferentIndividual(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null || op.target() == null) return false;
        toAdd.add(df.getOWLDifferentIndividualsAxiom(
                df.getOWLNamedIndividual(IRI.create(op.iri())),
                df.getOWLNamedIndividual(IRI.create(op.target()))));
        return true;
    }

    private boolean removeDifferentIndividual(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toRemove) {
        if (op.iri() == null || op.target() == null) return false;
        toRemove.add(df.getOWLDifferentIndividualsAxiom(
                df.getOWLNamedIndividual(IRI.create(op.iri())),
                df.getOWLNamedIndividual(IRI.create(op.target()))));
        return true;
    }

    private boolean createDatatype(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        if (op.iri() == null) return false;
        OWLDatatype dt = df.getOWLDatatype(IRI.create(op.iri()));
        toAdd.add(df.getOWLDeclarationAxiom(dt));
        if (op.label() != null && !op.label().isBlank()) {
            toAdd.add(df.getOWLAnnotationAssertionAxiom(
                    df.getRDFSLabel(), dt.getIRI(), df.getOWLLiteral(op.label())));
        }
        return true;
    }

    private boolean addObjectRestriction(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        Optional<OWLClass> subject = namedClass(op.iri(), df);
        if (subject.isEmpty() || op.property() == null || op.restrictionType() == null || op.target() == null) {
            return false;
        }
        OWLObjectPropertyExpression prop = df.getOWLObjectProperty(IRI.create(op.property()));
        OWLClassExpression restriction = buildObjectRestriction(df, prop, op.restrictionType(), op.target(), op.cardinality());
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
        if (subject.isEmpty() || op.property() == null || op.restrictionType() == null || op.target() == null) {
            return false;
        }
        OWLObjectPropertyExpression prop = df.getOWLObjectProperty(IRI.create(op.property()));
        OWLClassExpression restriction = buildObjectRestriction(df, prop, op.restrictionType(), op.target(), op.cardinality());
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

    private boolean addDataRestriction(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toAdd) {
        Optional<OWLClass> subject = namedClass(op.iri(), df);
        if (subject.isEmpty() || op.property() == null || op.restrictionType() == null || op.target() == null) {
            return false;
        }
        OWLDataPropertyExpression prop = df.getOWLDataProperty(IRI.create(op.property()));
        OWLClassExpression restriction = buildDataRestriction(df, prop, op.restrictionType(), op.target(), op.cardinality());
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

    private boolean removeDataRestriction(OWLDataFactory df, OntologyMutationService.MutationOp op, Set<OWLAxiom> toRemove) {
        Optional<OWLClass> subject = namedClass(op.iri(), df);
        if (subject.isEmpty() || op.property() == null || op.restrictionType() == null || op.target() == null) {
            return false;
        }
        OWLDataPropertyExpression prop = df.getOWLDataProperty(IRI.create(op.property()));
        OWLClassExpression restriction = buildDataRestriction(df, prop, op.restrictionType(), op.target(), op.cardinality());
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

    private OWLClassExpression buildObjectRestriction(OWLDataFactory df,
                                                      OWLObjectPropertyExpression property,
                                                      String restrictionType,
                                                      String fillerIri,
                                                      Integer cardinality) {
        return switch (restrictionType) {
            case "some" -> {
                Optional<OWLClass> filler = namedClass(fillerIri, df);
                yield filler.map(f -> df.getOWLObjectSomeValuesFrom(property, f)).orElse(null);
            }
            case "only" -> {
                Optional<OWLClass> filler = namedClass(fillerIri, df);
                yield filler.map(f -> df.getOWLObjectAllValuesFrom(property, f)).orElse(null);
            }
            case "value" -> df.getOWLObjectHasValue(property, df.getOWLNamedIndividual(IRI.create(fillerIri)));
            case "min", "max", "exactly" -> {
                Optional<OWLClass> filler = namedClass(fillerIri, df);
                if (filler.isEmpty()) yield null;
                int card = cardinality != null ? cardinality : 1;
                yield switch (restrictionType) {
                    case "min" -> df.getOWLObjectMinCardinality(card, property, filler.get());
                    case "max" -> df.getOWLObjectMaxCardinality(card, property, filler.get());
                    default -> df.getOWLObjectExactCardinality(card, property, filler.get());
                };
            }
            default -> null;
        };
    }

    private OWLClassExpression buildDataRestriction(OWLDataFactory df,
                                                    OWLDataPropertyExpression property,
                                                    String restrictionType,
                                                    String fillerIri,
                                                    Integer cardinality) {
        OWLDataRange dataRange = resolveDataRange(df, fillerIri);
        if (dataRange == null) {
            return null;
        }
        return switch (restrictionType) {
            case "some" -> df.getOWLDataSomeValuesFrom(property, dataRange);
            case "only" -> df.getOWLDataAllValuesFrom(property, dataRange);
            case "value" -> null;
            case "min", "max", "exactly" -> {
                int card = cardinality != null ? cardinality : 1;
                yield switch (restrictionType) {
                    case "min" -> df.getOWLDataMinCardinality(card, property, dataRange);
                    case "max" -> df.getOWLDataMaxCardinality(card, property, dataRange);
                    default -> df.getOWLDataExactCardinality(card, property, dataRange);
                };
            }
            default -> null;
        };
    }

    private OWLDataRange resolveDataRange(OWLDataFactory df, String fillerIri) {
        if (fillerIri == null || fillerIri.isBlank()) {
            return null;
        }
        return df.getOWLDatatype(IRI.create(fillerIri));
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
        if (ontology.containsAnnotationPropertyInSignature(propIri, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)) {
            String resolved = rangeIri.startsWith("xsd:") ? "http://www.w3.org/2001/XMLSchema#" + rangeIri.substring(4) : rangeIri;
            toAdd.add(df.getOWLAnnotationPropertyRangeAxiom(df.getOWLAnnotationProperty(propIri), IRI.create(resolved)));
            return true;
        }
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

    private boolean addSubPropertyOf(OWLOntology ontology, OWLDataFactory df, String subIri, String superIri, Set<OWLAxiom> toAdd) {
        if (subIri == null || superIri == null) {
            return false;
        }
        if (superIri.contains("XMLSchema#") || subIri.contains("XMLSchema#")) {
            return false;
        }
        IRI sub = IRI.create(subIri);
        IRI sup = IRI.create(superIri);
        if (ontology.containsAnnotationPropertyInSignature(sub, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)) {
            toAdd.add(df.getOWLSubAnnotationPropertyOfAxiom(df.getOWLAnnotationProperty(sub), df.getOWLAnnotationProperty(sup)));
            return true;
        }
        if (ontology.containsDataPropertyInSignature(sub, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)) {
            toAdd.add(df.getOWLSubDataPropertyOfAxiom(df.getOWLDataProperty(sub), df.getOWLDataProperty(sup)));
            return true;
        }
        toAdd.add(df.getOWLSubObjectPropertyOfAxiom(df.getOWLObjectProperty(sub), df.getOWLObjectProperty(sup)));
        return true;
    }

    private boolean removeSubPropertyOf(OWLOntology ontology, OWLDataFactory df, String subIri, String superIri, Set<OWLAxiom> toRemove) {
        if (subIri == null || superIri == null) {
            return false;
        }
        IRI sub = IRI.create(subIri);
        IRI sup = IRI.create(superIri);
        if (ontology.containsAnnotationPropertyInSignature(sub, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)) {
            toRemove.add(df.getOWLSubAnnotationPropertyOfAxiom(df.getOWLAnnotationProperty(sub), df.getOWLAnnotationProperty(sup)));
            return true;
        }
        if (ontology.containsDataPropertyInSignature(sub, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)) {
            toRemove.add(df.getOWLSubDataPropertyOfAxiom(df.getOWLDataProperty(sub), df.getOWLDataProperty(sup)));
            return true;
        }
        toRemove.add(df.getOWLSubObjectPropertyOfAxiom(df.getOWLObjectProperty(sub), df.getOWLObjectProperty(sup)));
        return true;
    }

    private Optional<OWLClass> namedClass(String iri, OWLDataFactory df) {
        if (iri == null || iri.isBlank() || iri.startsWith("_:")) {
            return Optional.empty();
        }
        return Optional.of(df.getOWLClass(IRI.create(iri)));
    }

    /**
     * Finds the SubClassOf axiom for {@code anchor}'s complex/anonymous superclass whose
     * Manchester rendering matches {@code definition} — the same text {@code DesktopHierarchyService}
     * rendered for this row via {@code classExpressionToAxiomMap}'s fallback id path.
     */
    private Optional<OWLSubClassOfAxiom> findAnonymousSubClassOfAxiom(OWLOntology ont, OWLClass anchor, String definition) {
        if (definition == null || definition.isBlank()) {
            return Optional.empty();
        }
        return ont.subClassAxiomsForSubClass(anchor)
                .filter(ax -> ax.getSuperClass().isAnonymous())
                .filter(ax -> definition.equals(OwlApiQuerySupport.classExpressionToManchester(ont, ax.getSuperClass())))
                .findFirst();
    }

    /**
     * Finds the EquivalentClasses axiom containing a complex/anonymous expression for
     * {@code anchor} whose Manchester rendering matches {@code definition}.
     */
    private Optional<OWLEquivalentClassesAxiom> findAnonymousEquivalentClassAxiom(OWLOntology ont, OWLClass anchor, String definition) {
        if (definition == null || definition.isBlank()) {
            return Optional.empty();
        }
        return ont.equivalentClassesAxioms(anchor)
                .filter(ax -> ax.classExpressions().anyMatch(ce -> !ce.equals(anchor) && ce.isAnonymous()
                        && definition.equals(OwlApiQuerySupport.classExpressionToManchester(ont, ce))))
                .findFirst();
    }

    /**
     * Finds the General Class Axiom (anonymous-subject SubClassOf involving {@code anchor} as
     * superclass, or nested inside a complex expression that does) whose rendering matches
     * {@code definition} — the exact "subExpr SubClassOf anchorLabel" text
     * {@code DesktopHierarchyService.supplementClassDetails} rendered for the GCI row.
     */
    private Optional<OWLSubClassOfAxiom> findGciAxiom(OWLOntology ont, OWLClass anchor, String definition) {
        if (definition == null || definition.isBlank()) {
            return Optional.empty();
        }
        String anchorLabel = OwlApiQuerySupport.getLabel(ont, anchor.getIRI());
        for (OWLSubClassOfAxiom ax : ont.getAxioms(AxiomType.SUBCLASS_OF)) {
            OWLClassExpression sub = ax.getSubClass();
            if (!sub.isAnonymous()) {
                continue;
            }
            if (!ax.getSuperClass().equals(anchor) && !OwlApiQuerySupport.expressionReferencesClass(sub, anchor)) {
                continue;
            }
            String rendered = OwlApiQuerySupport.classExpressionToManchester(ont, sub) + " SubClassOf " + anchorLabel;
            if (definition.equals(rendered)) {
                return Optional.of(ax);
            }
        }
        return Optional.empty();
    }

    /**
     * "xsd:string" (short form) is the frontend's default placeholder for "no explicit datatype",
     * not a real IRI — {@code IRI.create("xsd:string")} would create a bogus IRI literally equal
     * to that string instead of resolving the xsd: prefix, so a literal built with it would never
     * equal the plain-literal (df.getOWLLiteral(value)) that OWLAPI actually produces by default
     * for untyped strings. That mismatch breaks removal-by-equality in updateAnnotation: the old
     * literal is never found, so the delete silently no-ops while the add still runs, leaving two
     * annotation values. Mirrors OntologyMetadataService#formatLiteral's same guard on the SPARQL
     * side.
     */
    private boolean isDefaultStringDatatype(String datatype) {
        return datatype.equals("xsd:string") || datatype.endsWith("#string");
    }

    private OWLLiteral dataLiteral(OWLDataFactory df, String value, String lang, String datatype) {
        if (datatype != null && !datatype.isBlank() && !isDefaultStringDatatype(datatype)) {
            return df.getOWLLiteral(value, df.getOWLDatatype(IRI.create(datatype)));
        }
        if (lang != null && !lang.isBlank()) {
            return df.getOWLLiteral(value, lang);
        }
        return df.getOWLLiteral(value);
    }

    private OWLAnnotationValue literalValue(OWLDataFactory df, String value, String lang, String datatype) {
        if (datatype != null && !datatype.isBlank() && !isDefaultStringDatatype(datatype)) {
            return df.getOWLLiteral(value, df.getOWLDatatype(IRI.create(datatype)));
        }
        if (lang != null && !lang.isBlank()) {
            return df.getOWLLiteral(value, lang);
        }
        return df.getOWLLiteral(value);
    }
}
