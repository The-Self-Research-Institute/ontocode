package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class OwlFactExtractor {

    private final OWLOntology ontology;
    private final OWLReasoner reasoner;
    private final Deadline deadline;
    private final int maxGroup;
    private final ExtractedFacts out = new ExtractedFacts();
    private final Map<OWLNamedIndividual, OWLNamedIndividual> repOf = new LinkedHashMap<>();

    OwlFactExtractor(OWLOntology ontology, OWLReasoner reasoner, Deadline deadline, int maxGroup) {
        this.maxGroup = maxGroup;
        this.ontology = ontology;
        this.reasoner = reasoner;
        this.deadline = deadline;
    }

    ExtractedFacts extract() {
        precompute();
        classHierarchy();
        propertyHierarchy();
        List<OWLNamedIndividual> individuals = ontology.individualsInSignature()
                .sorted(Comparator.comparing(i -> i.getIRI().toString())).collect(Collectors.toList());
        groups(individuals);
        List<OWLObjectProperty> objectProperties = ontology.objectPropertiesInSignature()
                .filter(p -> !p.isTopEntity() && !p.isBottomEntity()).collect(Collectors.toList());
        List<OWLDataProperty> dataProperties = ontology.dataPropertiesInSignature()
                .filter(p -> !p.isTopEntity() && !p.isBottomEntity()).collect(Collectors.toList());
        statedDifferences();
        for (OWLNamedIndividual i : individuals) {
            if (!i.equals(repOf.get(i))) {
                continue;
            }
            deadline.check();
            individualFacts(i, objectProperties, dataProperties);
        }
        return out;
    }

    private void individualFacts(OWLNamedIndividual i, List<OWLObjectProperty> objectProperties,
                                 List<OWLDataProperty> dataProperties) {
        IRI s = RdfTerms.iri(i);
        reasoner.getTypes(i, false).entities().filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .forEach(c -> out.facts.add(RdfTerms.triple(s, RdfTerms.TYPE, RdfTerms.iri(c))));
        for (OWLObjectProperty p : objectProperties) {
            IRI pi = RdfTerms.iri(p);
            for (Node<OWLNamedIndividual> node : reasoner.getObjectPropertyValues(i, p).getNodes()) {
                OWLNamedIndividual target = repOfNode(node);
                if (target != null) {
                    out.facts.add(RdfTerms.triple(s, pi, RdfTerms.iri(target)));
                }
            }
        }
        for (OWLDataProperty d : dataProperties) {
            IRI di = RdfTerms.iri(d);
            reasoner.getDataPropertyValues(i, d).forEach(v -> out.facts.add(RdfTerms.triple(s, di, RdfTerms.literal(v))));
        }
    }

    private void statedDifferences() {
        int limit = 200_000;
        for (org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom axiom
                : ontology.axioms(org.semanticweb.owlapi.model.AxiomType.DIFFERENT_INDIVIDUALS).collect(Collectors.toList())) {
            List<OWLNamedIndividual> items = axiom.individuals().filter(OWLNamedIndividual.class::isInstance)
                    .map(OWLNamedIndividual.class::cast).collect(Collectors.toList());
            for (OWLNamedIndividual a : items) {
                for (OWLNamedIndividual b : items) {
                    if (out.differentPairs.size() >= limit) {
                        return;
                    }
                    OWLNamedIndividual ra = repOf.getOrDefault(a, a);
                    OWLNamedIndividual rb = repOf.getOrDefault(b, b);
                    if (!ra.equals(rb)) {
                        out.differentPairs.add(new IRI[] {RdfTerms.iri(ra), RdfTerms.iri(rb)});
                    }
                }
            }
        }
    }

    private OWLNamedIndividual repOfNode(Node<OWLNamedIndividual> node) {
        return node.entities().filter(OWLNamedIndividual.class::isInstance).findFirst()
                .map(e -> repOf.getOrDefault(e, e)).orElse(null);
    }

    private void precompute() {
        Set<InferenceType> wanted = EnumSet.of(InferenceType.CLASS_HIERARCHY, InferenceType.OBJECT_PROPERTY_HIERARCHY,
                InferenceType.DATA_PROPERTY_HIERARCHY, InferenceType.CLASS_ASSERTIONS,
                InferenceType.OBJECT_PROPERTY_ASSERTIONS, InferenceType.SAME_INDIVIDUAL);
        wanted.retainAll(reasoner.getPrecomputableInferenceTypes());
        reasoner.precomputeInferences(wanted.toArray(new InferenceType[0]));
    }

    private void classHierarchy() {
        Set<OWLClass> unsatisfiable = reasoner.getUnsatisfiableClasses().entities().collect(Collectors.toSet());
        List<OWLClass> classes = ontology.classesInSignature()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing() && !unsatisfiable.contains(c)).collect(Collectors.toList());
        int n = 0;
        for (OWLClass c : classes) {
            if ((++n & 255) == 0) {
                deadline.check();
            }
            IRI ci = RdfTerms.iri(c);
            reasoner.getEquivalentClasses(c).entities().filter(e -> !e.equals(c) && !e.isOWLThing())
                    .forEach(e -> out.structure.add(RdfTerms.triple(ci, RdfTerms.EQUIVALENT_CLASS, RdfTerms.iri(e))));
            reasoner.getSuperClasses(c, false).entities().filter(e -> !e.isOWLThing() && !e.equals(c))
                    .forEach(e -> out.structure.add(RdfTerms.triple(ci, RdfTerms.SUB_CLASS, RdfTerms.iri(e))));
        }
    }

    private void propertyHierarchy() {
        ontology.objectPropertiesInSignature().filter(p -> !p.isTopEntity() && !p.isBottomEntity()).forEach(p -> {
            IRI pi = RdfTerms.iri(p);
            reasoner.getEquivalentObjectProperties(p).entities().filter(e -> !e.isAnonymous() && !e.equals(p))
                    .forEach(e -> out.structure.add(RdfTerms.triple(pi, RdfTerms.EQUIVALENT_PROPERTY, RdfTerms.iri(e.asOWLObjectProperty()))));
            reasoner.getSuperObjectProperties(p, false).entities()
                    .filter(e -> !e.isAnonymous() && !e.isTopEntity() && !e.equals(p))
                    .forEach(e -> out.structure.add(RdfTerms.triple(pi, RdfTerms.SUB_PROPERTY, RdfTerms.iri(e.asOWLObjectProperty()))));
        });
        ontology.dataPropertiesInSignature().filter(p -> !p.isTopEntity() && !p.isBottomEntity()).forEach(p -> {
            IRI pi = RdfTerms.iri(p);
            reasoner.getEquivalentDataProperties(p).entities().filter(e -> !e.equals(p))
                    .forEach(e -> out.structure.add(RdfTerms.triple(pi, RdfTerms.EQUIVALENT_PROPERTY, RdfTerms.iri(e))));
            reasoner.getSuperDataProperties(p, false).entities().filter(e -> !e.isTopEntity() && !e.equals(p))
                    .forEach(e -> out.structure.add(RdfTerms.triple(pi, RdfTerms.SUB_PROPERTY, RdfTerms.iri(e))));
        });
    }

    private void groups(List<OWLNamedIndividual> individuals) {
        Set<OWLNamedIndividual> seen = new HashSet<>();
        for (OWLNamedIndividual i : individuals) {
            if (!seen.add(i)) {
                continue;
            }
            List<OWLNamedIndividual> members = new ArrayList<>(reasoner.getSameIndividuals(i).entities()
                    .filter(OWLNamedIndividual.class::isInstance).collect(Collectors.toList()));
            if (!members.contains(i)) {
                members.add(i);
            }
            members.sort(Comparator.comparing(e -> e.getIRI().toString()));
            OWLNamedIndividual rep = members.get(0);
            for (OWLNamedIndividual m : members) {
                seen.add(m);
                repOf.put(m, rep);
            }
            if (members.size() < 2) {
                continue;
            }
            out.largestGroup = Math.max(out.largestGroup, members.size());
            out.groupCount++;
            if (members.size() > maxGroup) {
                out.oversizeGroups.add(rep.getIRI() + " (" + members.size() + " names)");
                continue;
            }
            List<IRI> names = members.stream().map(RdfTerms::iri).collect(Collectors.toList());
            for (IRI name : names) {
                out.namesOf.put(name, names);
            }
        }
    }
}
