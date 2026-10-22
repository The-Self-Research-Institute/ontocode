package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDatatypeDefinitionAxiom;
import org.semanticweb.owlapi.model.OWLHasKeyAxiom;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLNaryClassAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.owlEditor.model.merge.ConflictResolution;
import self.research.ontology.owlEditor.model.merge.ResolutionAction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class OntologyHeaderMerge {

    public static final String ONTOLOGY_IRI = "header:ontologyIri";
    public static final String VERSION_IRI = "header:versionIri";
    public static final String ANNOTATIONS = "header:ontologyAnnotations";
    public static final String IMPORTS = "header:imports";
    public static final String OTHER_AXIOMS = "other-axioms";

    static final String SCRATCH_PREFIX = "http://ontocode.org/merge-scratch/";

    private static final List<AxiomType<?>> CANDIDATE_TYPES = List.of(
            AxiomType.SUBCLASS_OF, AxiomType.EQUIVALENT_CLASSES, AxiomType.DISJOINT_CLASSES,
            AxiomType.DATATYPE_DEFINITION, AxiomType.HAS_KEY, AxiomType.SUB_PROPERTY_CHAIN_OF,
            AxiomType.SWRL_RULE, AxiomType.SUB_OBJECT_PROPERTY);

    private OntologyHeaderMerge() {
    }

    public record Header(IRI ontologyIri,
                         IRI versionIri,
                         Set<OWLAnnotation> annotations,
                         Set<OWLImportsDeclaration> imports,
                         Set<OWLAxiom> otherAxioms,
                         Predicate<OWLAxiom> contains) {

        public static Header of(OWLOntologyID id, OWLOntology ontology) {
            return new Header(id.getOntologyIRI().orElse(null), id.getVersionIRI().orElse(null),
                    new LinkedHashSet<>(ontology.getAnnotations()),
                    new LinkedHashSet<>(ontology.getImportsDeclarations()),
                    OntologyHeaderMerge.otherAxioms(ontology), ontology::containsAxiom);
        }

        public static Header empty() {
            return new Header(null, null, Set.of(), Set.of(), Set.of(), axiom -> false);
        }

        public Header withSection(String key, Header from) {
            return switch (key) {
                case ONTOLOGY_IRI -> new Header(from.ontologyIri, versionIri, annotations, imports, otherAxioms, contains);
                case VERSION_IRI -> new Header(ontologyIri, from.versionIri, annotations, imports, otherAxioms, contains);
                case ANNOTATIONS -> new Header(ontologyIri, versionIri, from.annotations, imports, otherAxioms, contains);
                case IMPORTS -> new Header(ontologyIri, versionIri, annotations, from.imports, otherAxioms, contains);
                case OTHER_AXIOMS -> new Header(ontologyIri, versionIri, annotations, imports, from.otherAxioms, from.contains);
                default -> this;
            };
        }

        public static Header forChoices(Header source, Header target, Map<String, ConflictResolution> choices) {
            Header baseline = source;
            for (String key : List.of(ONTOLOGY_IRI, VERSION_IRI, ANNOTATIONS, IMPORTS, OTHER_AXIOMS)) {
                ResolutionAction action = actionOf(choices, key);
                if (action == ResolutionAction.KEEP_SOURCE) {
                    baseline = baseline.withSection(key, target);
                } else if (action == ResolutionAction.MERGE && isSetSection(key)) {
                    baseline = baseline.withSection(key, empty());
                }
            }
            return baseline;
        }
    }

    public record Field(String key, IRI baseline, IRI source, IRI target) {

        public String field() {
            return key.substring(key.indexOf(':') + 1);
        }

        public boolean sourceChanged() {
            return !Objects.equals(source, baseline);
        }

        public boolean targetChanged() {
            return !Objects.equals(target, baseline);
        }

        public boolean incoming() {
            return sourceChanged() && !Objects.equals(source, target);
        }

        public boolean conflict() {
            return incoming() && targetChanged();
        }

        public IRI resolve(ConflictResolution resolution) {
            if (!incoming()) {
                return target;
            }
            if (!conflict()) {
                return source;
            }
            return resolution != null && resolution.getAction() == ResolutionAction.KEEP_SOURCE ? source : target;
        }
    }

    public record Delta<T>(Set<T> added, Set<T> removed) {

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }

        public int size() {
            return added.size() + removed.size();
        }
    }

    public static boolean isSectionKey(String key) {
        return key != null && (key.startsWith("header:") || OTHER_AXIOMS.equals(key));
    }

    public static boolean isSetSection(String key) {
        return ANNOTATIONS.equals(key) || IMPORTS.equals(key) || OTHER_AXIOMS.equals(key);
    }

    public static boolean isScratch(IRI iri) {
        return iri != null && iri.toString().startsWith(SCRATCH_PREFIX);
    }

    public static List<Field> fields(Header baseline, Header source, Header target) {
        IRI base = baseline.ontologyIri();
        IRI sourceIri = isScratch(source.ontologyIri()) ? base : source.ontologyIri();
        IRI targetIri = isScratch(target.ontologyIri()) ? base : target.ontologyIri();
        return List.of(
                new Field(ONTOLOGY_IRI, base, sourceIri, targetIri),
                new Field(VERSION_IRI, baseline.versionIri(), source.versionIri(), target.versionIri()));
    }

    public static <T> Delta<T> changes(Set<T> baseline, Set<T> side) {
        return new Delta<>(only(side, item -> !baseline.contains(item)), only(baseline, item -> !side.contains(item)));
    }

    public static <T> Delta<T> delta(Set<T> baseline, Set<T> source, Set<T> target) {
        Delta<T> changed = changes(baseline, source);
        return new Delta<>(only(changed.added(), item -> !target.contains(item)), only(changed.removed(), target::contains));
    }

    public static Delta<OWLAxiom> otherAxiomChanges(Header baseline, Header side) {
        return new Delta<>(only(side.otherAxioms(), axiom -> !baseline.contains().test(axiom)),
                only(baseline.otherAxioms(), axiom -> !side.contains().test(axiom)));
    }

    public static Delta<OWLAxiom> otherAxiomDelta(Header baseline, Header source, Header target) {
        Delta<OWLAxiom> changed = otherAxiomChanges(baseline, source);
        return new Delta<>(only(changed.added(), axiom -> !target.contains().test(axiom)),
                only(changed.removed(), target.contains()));
    }

    public static OWLOntologyID apply(Header baseline, Header source, Header target, OWLOntology into,
                                      Map<String, ConflictResolution> resolutions) {
        List<Field> fields = fields(baseline, source, target);
        IRI ontologyIri = fields.get(0).resolve(resolutions.get(ONTOLOGY_IRI));
        IRI versionIri = fields.get(1).resolve(resolutions.get(VERSION_IRI));

        List<OWLOntologyChange> changes = new ArrayList<>();
        Delta<OWLAnnotation> annotations = delta(baseline.annotations(), source.annotations(), target.annotations());
        annotations.added().forEach(annotation -> changes.add(new AddOntologyAnnotation(into, annotation)));
        annotations.removed().forEach(annotation -> changes.add(new RemoveOntologyAnnotation(into, annotation)));
        Delta<OWLImportsDeclaration> imports = delta(baseline.imports(), source.imports(), target.imports());
        imports.added().forEach(declaration -> changes.add(new AddImport(into, declaration)));
        imports.removed().forEach(declaration -> changes.add(new RemoveImport(into, declaration)));
        Delta<OWLAxiom> axioms = otherAxiomDelta(baseline, source, target);
        axioms.added().forEach(axiom -> changes.add(new AddAxiom(into, axiom)));
        axioms.removed().forEach(axiom -> changes.add(new RemoveAxiom(into, axiom)));
        if (!changes.isEmpty()) {
            into.getOWLOntologyManager().applyChanges(changes);
        }

        if (ontologyIri == null) {
            return new OWLOntologyID();
        }
        return new OWLOntologyID(Optional.of(ontologyIri), Optional.ofNullable(versionIri));
    }

    public static Set<OWLAxiom> otherAxioms(OWLOntology ontology) {
        Set<OWLAxiom> result = new LinkedHashSet<>();
        for (AxiomType<?> type : CANDIDATE_TYPES) {
            for (OWLAxiom axiom : ontology.getAxioms(type)) {
                if (isOtherAxiom(axiom)) {
                    result.add(axiom);
                }
            }
        }
        return result;
    }

    static boolean isOtherAxiom(OWLAxiom axiom) {
        if (axiom.anonymousIndividuals().findAny().isPresent()) {
            return false;
        }
        if (axiom instanceof OWLSubClassOfAxiom sub) {
            return hasNoOwner(sub.getSubClass());
        }
        if (axiom instanceof OWLNaryClassAxiom nary) {
            return nary.getClassExpressions().stream().allMatch(OntologyHeaderMerge::hasNoOwner);
        }
        if (axiom instanceof OWLSubObjectPropertyOfAxiom sub) {
            return sub.getSubProperty().isAnonymous();
        }
        return axiom instanceof OWLDatatypeDefinitionAxiom
                || axiom instanceof OWLHasKeyAxiom
                || axiom instanceof OWLSubPropertyChainOfAxiom
                || axiom instanceof SWRLRule;
    }

    private static boolean hasNoOwner(OWLClassExpression expression) {
        return expression.isAnonymous() || expression.asOWLClass().isBuiltIn();
    }

    private static ResolutionAction actionOf(Map<String, ConflictResolution> choices, String key) {
        ConflictResolution choice = choices != null ? choices.get(key) : null;
        return choice != null ? choice.getAction() : null;
    }

    private static <T> Set<T> only(Set<T> items, Predicate<T> keep) {
        return items.stream().filter(keep).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
