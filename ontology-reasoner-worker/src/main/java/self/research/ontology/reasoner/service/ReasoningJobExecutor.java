package self.research.ontology.reasoner.service;

import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owl.explanation.api.Explanation;
import org.semanticweb.owl.explanation.api.ExplanationGenerator;
import org.semanticweb.owl.explanation.api.ExplanationGeneratorFactory;
import org.semanticweb.owl.explanation.impl.blackbox.checker.InconsistentOntologyExplanationGeneratorFactory;
import org.semanticweb.owl.explanation.impl.laconic.LaconicExplanationGeneratorFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.expression.ShortFormEntityChecker;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.BidirectionalShortFormProvider;
import org.semanticweb.owlapi.util.BidirectionalShortFormProviderAdapter;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReasoningJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReasoningJobExecutor.class);

    private final OntologySessionService sessionService;
    private final OWLReasonerFactory dlReasonerFactory;

    private static final OWLDataFactory EXPLANATION_DATA_FACTORY =
            OWLManager.createOWLOntologyManager().getOWLDataFactory();

    @Value("${ontocode.reasoner.justification-timeout-ms:15000}")
    private long justificationTimeoutMs;

    @Value("${ontocode.reasoner.max-justifications:5}")
    private int defaultMaxJustifications;

    public ReasoningJobExecutor(OntologySessionService sessionService) {
        this.sessionService = sessionService;
        OWLReasonerFactory factory = null;
        try {
            factory = openllet.owlapi.OpenlletReasonerFactory.getInstance();
        } catch (Exception e) {
            log.warn("Openllet not available for DL Query: {}", e.getMessage());
        }
        this.dlReasonerFactory = factory;
    }

    public Map<String, Object> execute(ReasoningJob job) throws Exception {
        return switch (job.getJobType()) {
            case REASONER_HIERARCHY -> executeHierarchy(job);
            case REASONER_OBJ_PROP_HIERARCHY -> executeObjPropHierarchy(job);
            case REASONER_DATA_PROP_HIERARCHY -> executeDataPropHierarchy(job);
            case DL_QUERY -> executeDlQuery(job);
            case REASONER_CONSISTENCY -> executeConsistency(job);
            case REASONER_CLASSIFY -> executeClassify(job);
            case REASONER_REALIZE -> executeRealize(job);
            case REASONER_RUN -> executeFullRun(job);
            case REASONER_INFERRED_AXIOMS -> executeInferredAxioms(job);
            case REASONER_EXPLAIN_INCONSISTENCY -> executeExplainInconsistency(job);
        };
    }

    private Map<String, Object> executeDlQuery(ReasoningJob job) throws Exception {
        if (dlReasonerFactory == null) {
            throw new IllegalStateException("No DL reasoner available on this worker");
        }
        List<String> types = job.getQueryTypes() == null || job.getQueryTypes().isEmpty()
                ? Arrays.asList("subclasses", "instances")
                : job.getQueryTypes();

        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), ReasonerType.OPENLLET, job.getOwnerEmail())) {
            OWLOntology ontology = session.ontology();
            OWLReasoner reasoner = session.reasoner();
            OWLClassExpression expr = parseClassExpression(ontology, job.getExpression());
            if (expr == null) {
                return Map.of(
                        "success", false,
                        "error", "Failed to parse class expression: " + job.getExpression(),
                        "hint", "Check Manchester OWL syntax. Examples: 'Person', 'Person and hasAge some integer'"
                );
            }

            Map<String, Object> results = new HashMap<>();
            for (String queryType : types) {
                switch (queryType.toLowerCase(Locale.ROOT)) {
                    case "directsuperclasses" ->
                            results.put("directSuperclasses", superClasses(reasoner, expr, ontology, true));
                    case "superclasses" ->
                            results.put("superclasses", superClasses(reasoner, expr, ontology, false));
                    case "equivalentclasses" ->
                            results.put("equivalentClasses", equivalentClasses(reasoner, expr, ontology));
                    case "directsubclasses" ->
                            results.put("directSubclasses", subClasses(reasoner, expr, ontology, true));
                    case "subclasses" ->
                            results.put("subclasses", subClasses(reasoner, expr, ontology, false));
                    case "instances" ->
                            results.put("instances", instances(reasoner, expr, ontology, false));
                    case "directinstances" ->
                            results.put("directInstances", instances(reasoner, expr, ontology, true));
                    default -> log.warn("Unknown DL query type: {}", queryType);
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("query", job.getExpression());
            response.put("queryType", types);
            response.put("results", results);
            if (session.downgradedWarning() != null) {
                response.put("downgradedWarning", session.downgradedWarning());
            }
            return response;
        }
    }

    private Map<String, Object> executeConsistency(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());
        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), type, job.getOwnerEmail())) {
            OWLReasoner reasoner = session.reasoner();
            ReasonerType effective = session.actualReasonerType() != null ? session.actualReasonerType() : type;
            boolean consistent = reasoner.isConsistent();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("consistent", consistent);
            result.put("reasonerType", effective.getDisplayName());
            result.put("projectId", job.getProjectId());
            if (session.downgradedWarning() != null) {
                result.put("downgradedWarning", session.downgradedWarning());
            }
            if (!consistent) {
                var unsat = reasoner.getUnsatisfiableClasses().getEntities();
                OWLDataFactory df = session.ontology().getOWLOntologyManager().getOWLDataFactory();
                unsat.remove(df.getOWLNothing());
                result.put("unsatisfiableClasses", unsat.stream()
                        .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", label(cls, session.ontology())))
                        .collect(Collectors.toList()));
            }
            return result;
        }
    }

    private Map<String, Object> executeExplainInconsistency(ReasoningJob job) {
        ReasonerType requestedType = parseReasonerType(job.getReasonerType());
        ReasonerType type = (requestedType == ReasonerType.ELK || requestedType == ReasonerType.STRUCTURAL)
                ? ReasonerType.HERMIT
                : requestedType;

        Map<String, Object> explanation = new HashMap<>();
        try (OntologySessionService.ReasoningSession session =
                     sessionService.openSession(job.getProjectId(), type, job.getOwnerEmail())) {
            ReasonerType effective = session.actualReasonerType() != null ? session.actualReasonerType() : type;
            OWLOntology ontology = session.ontology();
            int maxJustifications = job.getMaxJustifications() != null ? job.getMaxJustifications() : defaultMaxJustifications;
            String mode = job.getExplanationMode() != null && !job.getExplanationMode().isBlank()
                    ? job.getExplanationMode() : "regular";

            OWLOntology reasoningOntology = stripSwrlRules(ontology);
            OWLReasoner explainReasoner = EphemeralReasonerFactory.create(reasoningOntology, effective);
            boolean isConsistent;
            try {
                isConsistent = explainReasoner.isConsistent();
            } finally {
                try {
                    explainReasoner.dispose();
                } catch (Exception ignored) {
                }
            }

            explanation.put("success", true);
            explanation.put("isConsistent", isConsistent);
            explanation.put("usedReasoner", effective.getDisplayName());
            if (requestedType != effective) {
                explanation.put("reasonerUpgraded", true);
            }
            if (session.downgradedWarning() != null) {
                explanation.put("downgradedWarning", session.downgradedWarning());
            }

            if (isConsistent) {
                explanation.put("message", "Ontology is consistent - no explanation needed");
                explanation.put("causes", new ArrayList<>());
                return explanation;
            }

            log.info("Analyzing inconsistency causes for project {}", job.getProjectId());
            List<Map<String, Object>> causes = new ArrayList<>();

            Map<String, Object> globalNote = new HashMap<>();
            globalNote.put("type", "GLOBAL_INCONSISTENCY");
            globalNote.put("severity", "INFO");
            globalNote.put("title", "Every Class Is Vacuously Unsatisfiable");
            globalNote.put("description", "The ontology as a whole has no valid models, so every class is "
                    + "technically equivalent to owl:Nothing. The specific causes below identify which asserted "
                    + "axioms are actually responsible.");
            causes.add(globalNote);

            try {
                List<Map<String, Object>> disjointViolations = findDisjointClassViolations(ontology);
                if (!disjointViolations.isEmpty()) {
                    Map<String, Object> cause = new HashMap<>();
                    cause.put("type", "DISJOINT_VIOLATIONS");
                    cause.put("severity", "ERROR");
                    cause.put("title", "Disjoint Class Violations");
                    cause.put("description", "Found individuals or class assertions that violate disjointness constraints");
                    cause.put("violations", disjointViolations);
                    causes.add(cause);
                }
            } catch (Exception e) {
                log.error("Error checking disjoint violations", e);
            }

            try {
                List<Map<String, Object>> propertyViolations = findPropertyViolations(ontology);
                if (!propertyViolations.isEmpty()) {
                    Map<String, Object> cause = new HashMap<>();
                    cause.put("type", "PROPERTY_VIOLATIONS");
                    cause.put("severity", "ERROR");
                    cause.put("title", "Property Domain/Range Conflicts");
                    cause.put("description", "A property assertion entails a type for one of its endpoints "
                            + "(via ObjectPropertyDomain/Range) that is declared disjoint with a type the individual "
                            + "already has asserted");
                    cause.put("violations", propertyViolations);
                    causes.add(cause);
                }
            } catch (Exception e) {
                log.error("Error checking property violations", e);
            }

            try {
                List<Map<String, Object>> justifications = "laconic".equals(mode)
                        ? findLaconicJustifications(ontology, maxJustifications)
                        : findJustifications(ontology, maxJustifications);
                if (!justifications.isEmpty()) {
                    Map<String, Object> cause = new HashMap<>();
                    cause.put("type", "JUSTIFICATIONS");
                    cause.put("severity", "ERROR");
                    cause.put("title", "laconic".equals(mode)
                            ? "Laconic Inconsistency Justifications"
                            : "Minimal Inconsistency Justifications");
                    cause.put("description", "laconic".equals(mode)
                            ? "Each axiom below has been trimmed to just the part actually responsible for the "
                                + "contradiction, with unrelated conjuncts or restrictions removed."
                            : "Minimal sets of asserted axioms that each independently make "
                                + "the ontology inconsistent. Each explanation below is a self-contained, provably "
                                + "sufficient cause — removing any single axiom from it would resolve that path.");
                    cause.put("justifications", justifications);
                    causes.add(cause);
                }
            } catch (Exception e) {
                log.error("Error running justification search", e);
            }

            Map<String, Object> recommendations = new HashMap<>();
            recommendations.put("type", "RECOMMENDATIONS");
            recommendations.put("title", "How to Fix");
            List<String> tips = new ArrayList<>();
            tips.add("Review the disjoint/property violations listed above");
            tips.add("Check for conflicting disjointness declarations");
            tips.add("Examine cardinality restrictions (min/max constraints)");
            tips.add("Verify property domain and range definitions");
            tips.add("Look for circular or contradictory class definitions");
            recommendations.put("tips", tips);
            causes.add(recommendations);

            explanation.put("causes", causes);
            explanation.put("totalIssues", causes.stream()
                    .filter(c -> !"RECOMMENDATIONS".equals(c.get("type")) && !"GLOBAL_INCONSISTENCY".equals(c.get("type")))
                    .count());

            return explanation;
        } catch (Exception e) {
            log.error("Error explaining inconsistency for project {}", job.getProjectId(), e);
            explanation.put("success", false);
            explanation.put("error", e.getMessage());
            return explanation;
        }
    }

    private List<Map<String, Object>> findDisjointClassViolations(OWLOntology ontology) {
        List<Map<String, Object>> violations = new ArrayList<>();

        List<OWLDisjointClassesAxiom> disjointAxioms =
                new ArrayList<>(ontology.getAxioms(AxiomType.DISJOINT_CLASSES));
        if (disjointAxioms.isEmpty()) {
            return violations;
        }

        Map<OWLNamedIndividual, TypeProvenance> provenanceByIndividual = new HashMap<>();
        for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
            provenanceByIndividual.put(individual, getAssertedTypesClosureWithProvenance(ontology, individual));
        }

        for (OWLDisjointClassesAxiom axiom : disjointAxioms) {
            List<OWLClass> disjointClasses = axiom.getClassesInSignature().stream()
                    .filter(c -> !c.isAnonymous())
                    .collect(Collectors.toList());

            if (disjointClasses.size() < 2) {
                continue;
            }

            for (Map.Entry<OWLNamedIndividual, TypeProvenance> entry : provenanceByIndividual.entrySet()) {
                TypeProvenance provenance = entry.getValue();
                List<OWLClass> violatingClasses = disjointClasses.stream()
                        .filter(provenance.cameFrom::containsKey)
                        .collect(Collectors.toList());

                if (violatingClasses.size() > 1) {
                    Map<String, Object> violation = new HashMap<>();
                    violation.put("individual", label(entry.getKey(), ontology));
                    violation.put("individualIri", entry.getKey().getIRI().toString());
                    List<String> classLabels = violatingClasses.stream()
                            .map(c -> label(c, ontology))
                            .collect(Collectors.toList());
                    violation.put("disjointClasses", classLabels);
                    List<Map<String, Object>> typeDerivations = violatingClasses.stream()
                            .map(c -> {
                                Map<String, Object> derivation = new HashMap<>();
                                derivation.put("class", label(c, ontology));
                                derivation.put("via", buildDerivationChain(c, provenance, ontology));
                                return derivation;
                            })
                            .collect(Collectors.toList());
                    violation.put("suggestedFix", buildDisjointFixSuggestion(label(entry.getKey(), ontology), typeDerivations));
                    violation.put("typeDerivations", typeDerivations);
                    violations.add(violation);

                    if (violations.size() >= 5) {
                        return violations; // Limit results
                    }
                }
            }
        }

        return violations;
    }

    private String buildDisjointFixSuggestion(String individualLabel, List<Map<String, Object>> typeDerivations) {
        List<String> direct = new ArrayList<>();
        List<String> inherited = new ArrayList<>();
        for (Map<String, Object> d : typeDerivations) {
            String className = (String) d.get("class");
            String via = (String) d.get("via");
            if (via == null) {
                direct.add(className);
            } else {
                inherited.add(className + " (via " + via + ")");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("These classes are declared mutually exclusive, so ")
                .append(individualLabel).append(" cannot belong to more than one.");

        if (!direct.isEmpty()) {
            sb.append(" ").append(direct.size() == 1 ? "One option: remove " : "One option: remove all but one of ")
                    .append(individualLabel).append("'s direct membership in ")
                    .append(String.join(" and ", direct)).append(".");
        }
        if (!inherited.isEmpty()) {
            sb.append(" Note that ").append(String.join(" and ", inherited))
                    .append(" wasn't asserted directly — it came from an inheritance chain, "
                            + "so the real fix might be higher up: reconsider that inherited rule instead of editing "
                            + individualLabel).append(" directly.");
        }
        return sb.toString();
    }

    private Set<OWLClass> getAssertedTypesClosure(OWLOntology ontology, OWLNamedIndividual individual) {
        return getAssertedTypesClosureWithProvenance(ontology, individual).cameFrom.keySet();
    }

    private static final class TypeProvenance {
        final Map<OWLClass, OWLClass> cameFrom;
        final Map<OWLClass, String> unionDerivations;

        TypeProvenance(Map<OWLClass, OWLClass> cameFrom, Map<OWLClass, String> unionDerivations) {
            this.cameFrom = cameFrom;
            this.unionDerivations = unionDerivations;
        }
    }

    private TypeProvenance getAssertedTypesClosureWithProvenance(OWLOntology ontology, OWLNamedIndividual individual) {
        Map<OWLClass, OWLClass> cameFrom = new LinkedHashMap<>();
        Map<OWLClass, String> unionDerivations = new LinkedHashMap<>();
        Deque<OWLClass> frontier = new ArrayDeque<>();

        for (OWLClassAssertionAxiom ax : ontology.getClassAssertionAxioms(individual)) {
            OWLClassExpression ce = ax.getClassExpression();
            if (!ce.isAnonymous()) {
                OWLClass cls = ce.asOWLClass();
                if (!cameFrom.containsKey(cls)) {
                    cameFrom.put(cls, null);
                    frontier.push(cls);
                }
            }
        }

        while (!frontier.isEmpty()) {
            OWLClass current = frontier.pop();

            for (OWLSubClassOfAxiom ax : ontology.getSubClassAxiomsForSubClass(current)) {
                OWLClassExpression sup = ax.getSuperClass();
                if (!sup.isAnonymous()) {
                    OWLClass supCls = sup.asOWLClass();
                    if (!cameFrom.containsKey(supCls)) {
                        cameFrom.put(supCls, current);
                        frontier.push(supCls);
                    }
                }
            }

            for (OWLEquivalentClassesAxiom ax : ontology.getEquivalentClassesAxioms(current)) {
                for (OWLClassExpression member : ax.getClassExpressions()) {
                    if (!member.isAnonymous()) {
                        OWLClass memberCls = member.asOWLClass();
                        if (!cameFrom.containsKey(memberCls)) {
                            cameFrom.put(memberCls, current);
                            frontier.push(memberCls);
                        }
                    }
                }
            }

            for (OWLDisjointUnionAxiom duAxiom : ontology.getDisjointUnionAxioms(current)) {
                List<OWLClass> disjuncts = duAxiom.getClassExpressions().stream()
                        .filter(ce -> !ce.isAnonymous())
                        .map(OWLClassExpression::asOWLClass)
                        .collect(Collectors.toList());
                if (disjuncts.isEmpty()) {
                    continue;
                }

                Set<OWLClass> commonAncestors = null;
                for (OWLClass disjunct : disjuncts) {
                    Set<OWLClass> supers = computeAllSuperclasses(ontology, disjunct);
                    commonAncestors = (commonAncestors == null) ? new HashSet<>(supers)
                            : intersect(commonAncestors, supers);
                }
                if (commonAncestors == null) {
                    continue;
                }

                String disjunctLabels = disjuncts.stream()
                        .map(d -> label(d, ontology))
                        .collect(Collectors.joining(" or "));
                for (OWLClass ancestor : commonAncestors) {
                    if (!cameFrom.containsKey(ancestor)) {
                        cameFrom.put(ancestor, current);
                        unionDerivations.put(ancestor, label(current, ontology) + " is a disjoint union of "
                                + disjunctLabels + " — both are " + label(ancestor, ontology));
                        frontier.push(ancestor);
                    }
                }
            }
        }

        return new TypeProvenance(cameFrom, unionDerivations);
    }

    private Set<OWLClass> computeAllSuperclasses(OWLOntology ontology, OWLClass start) {
        Set<OWLClass> result = new HashSet<>();
        Deque<OWLClass> frontier = new ArrayDeque<>();
        result.add(start);
        frontier.push(start);
        while (!frontier.isEmpty()) {
            OWLClass current = frontier.pop();
            for (OWLSubClassOfAxiom ax : ontology.getSubClassAxiomsForSubClass(current)) {
                OWLClassExpression sup = ax.getSuperClass();
                if (!sup.isAnonymous() && result.add(sup.asOWLClass())) {
                    frontier.push(sup.asOWLClass());
                }
            }
            for (OWLEquivalentClassesAxiom ax : ontology.getEquivalentClassesAxioms(current)) {
                for (OWLClassExpression member : ax.getClassExpressions()) {
                    if (!member.isAnonymous() && result.add(member.asOWLClass())) {
                        frontier.push(member.asOWLClass());
                    }
                }
            }
        }
        return result;
    }

    private Set<OWLClass> intersect(Set<OWLClass> a, Set<OWLClass> b) {
        Set<OWLClass> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    private String buildDerivationChain(OWLClass cls, TypeProvenance provenance, OWLOntology ontology) {
        if (provenance.unionDerivations.containsKey(cls)) {
            return provenance.unionDerivations.get(cls);
        }
        if (provenance.cameFrom.get(cls) == null) {
            return null;
        }
        List<String> chain = new ArrayList<>();
        OWLClass current = cls;
        while (current != null) {
            chain.add(0, label(current, ontology));
            current = provenance.cameFrom.get(current);
        }
        return String.join(" ⊑ ", chain);
    }

    private List<Map<String, Object>> findPropertyViolations(OWLOntology ontology) {
        List<Map<String, Object>> violations = new ArrayList<>();

        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
            Set<OWLClass> domains = ontology.getObjectPropertyDomainAxioms(prop).stream()
                    .map(OWLObjectPropertyDomainAxiom::getDomain)
                    .filter(d -> !d.isAnonymous())
                    .map(OWLClassExpression::asOWLClass)
                    .collect(Collectors.toSet());

            Set<OWLClass> ranges = ontology.getObjectPropertyRangeAxioms(prop).stream()
                    .map(OWLObjectPropertyRangeAxiom::getRange)
                    .filter(r -> !r.isAnonymous())
                    .map(OWLClassExpression::asOWLClass)
                    .collect(Collectors.toSet());

            if (domains.isEmpty() && ranges.isEmpty()) {
                continue;
            }

            for (OWLObjectPropertyAssertionAxiom assertion : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
                if (assertion.getProperty().isAnonymous() || !assertion.getProperty().asOWLObjectProperty().equals(prop)) {
                    continue;
                }
                if (assertion.getSubject().isAnonymous() || assertion.getObject().isAnonymous()) {
                    continue;
                }
                OWLNamedIndividual subject = assertion.getSubject().asOWLNamedIndividual();
                OWLNamedIndividual object = assertion.getObject().asOWLNamedIndividual();

                if (!domains.isEmpty()) {
                    Set<OWLClass> subjectTypes = getAssertedTypesClosure(ontology, subject);
                    for (OWLClass domain : domains) {
                        OWLClass conflict = findDisjointConflict(ontology, subjectTypes, domain);
                        if (conflict != null) {
                            violations.add(buildPropertyViolation(prop, "domain", subject, domain, conflict, ontology));
                            if (violations.size() >= 5) {
                                return violations;
                            }
                        }
                    }
                }

                if (!ranges.isEmpty()) {
                    Set<OWLClass> objectTypes = getAssertedTypesClosure(ontology, object);
                    for (OWLClass range : ranges) {
                        OWLClass conflict = findDisjointConflict(ontology, objectTypes, range);
                        if (conflict != null) {
                            violations.add(buildPropertyViolation(prop, "range", object, range, conflict, ontology));
                            if (violations.size() >= 5) {
                                return violations;
                            }
                        }
                    }
                }
            }
        }

        return violations;
    }

    private OWLClass findDisjointConflict(OWLOntology ontology, Set<OWLClass> assertedTypes, OWLClass required) {
        if (assertedTypes.contains(required)) {
            return null;
        }
        for (OWLDisjointClassesAxiom axiom : ontology.getDisjointClassesAxioms(required)) {
            for (OWLClass other : axiom.getClassesInSignature()) {
                if (!other.equals(required) && assertedTypes.contains(other)) {
                    return other;
                }
            }
        }
        return null;
    }

    private Map<String, Object> buildPropertyViolation(OWLObjectProperty prop, String constraintKind,
            OWLNamedIndividual individual, OWLClass required, OWLClass conflict, OWLOntology ontology) {
        Map<String, Object> violation = new HashMap<>();
        String individualLabel = label(individual, ontology);
        String propLabel = label(prop, ontology);
        String requiredLabel = label(required, ontology);
        String conflictLabel = label(conflict, ontology);
        violation.put("property", propLabel);
        violation.put("propertyIri", prop.getIRI().toString());
        violation.put("constraintKind", constraintKind);
        violation.put("individual", individualLabel);
        violation.put("individualIri", individual.getIRI().toString());
        violation.put("requiredClass", requiredLabel);
        violation.put("conflictingClass", conflictLabel);
        violation.put("suggestedFix", "The " + constraintKind + " of " + propLabel + " requires " + individualLabel
                + " to be a " + requiredLabel + ", but it's already asserted as " + conflictLabel
                + ", which is disjoint with " + requiredLabel + ". Either remove " + individualLabel + "'s "
                + conflictLabel + " type, stop using it with " + propLabel + ", or reconsider whether "
                + requiredLabel + " and " + conflictLabel + " should really be disjoint.");
        return violation;
    }

    private List<Map<String, Object>> findJustifications(OWLOntology ontology, int limit) {
        OWLOntology reasoningOntology = stripSwrlRules(ontology);
        InconsistentOntologyExplanationGeneratorFactory factory =
                new InconsistentOntologyExplanationGeneratorFactory(
                        new ReasonerFactory(), EXPLANATION_DATA_FACTORY, OWLManager::createOWLOntologyManager, justificationTimeoutMs);
        ExplanationGenerator<OWLAxiom> generator = factory.createExplanationGenerator(reasoningOntology);
        OWLAxiom entailment = EXPLANATION_DATA_FACTORY.getOWLSubClassOfAxiom(
                EXPLANATION_DATA_FACTORY.getOWLThing(), EXPLANATION_DATA_FACTORY.getOWLNothing());
        Set<Explanation<OWLAxiom>> explanations = generator.getExplanations(entailment, limit);
        return renderJustifications(explanations, ontology);
    }

    private List<Map<String, Object>> findLaconicJustifications(OWLOntology ontology, int limit) {
        OWLOntology reasoningOntology = stripSwrlRules(ontology);

        InconsistentOntologyExplanationGeneratorFactory baseFactory =
                new InconsistentOntologyExplanationGeneratorFactory(
                        new ReasonerFactory(), EXPLANATION_DATA_FACTORY, OWLManager::createOWLOntologyManager, justificationTimeoutMs);

        ExplanationGeneratorFactory<OWLAxiom> laconicFactory =
                new LaconicExplanationGeneratorFactory<OWLAxiom>(baseFactory, OWLManager::createOWLOntologyManager);

        ExplanationGenerator<OWLAxiom> generator = laconicFactory.createExplanationGenerator(reasoningOntology);
        OWLAxiom entailment = EXPLANATION_DATA_FACTORY.getOWLSubClassOfAxiom(
                EXPLANATION_DATA_FACTORY.getOWLThing(), EXPLANATION_DATA_FACTORY.getOWLNothing());
        Set<Explanation<OWLAxiom>> explanations = generator.getExplanations(entailment, limit);
        return renderJustifications(explanations, ontology);
    }

    private List<Map<String, Object>> renderJustifications(Set<Explanation<OWLAxiom>> explanations, OWLOntology ontology) {
        if (explanations.isEmpty()) {
            return Collections.emptyList();
        }

        List<Set<OWLAxiom>> allAxiomSets = explanations.stream()
                .map(Explanation::getAxioms)
                .collect(Collectors.toList());

        List<Map<String, Object>> justifications = new ArrayList<>();
        int i = 1;
        for (Explanation<OWLAxiom> explanation : explanations) {
            Map<String, Object> justification = new HashMap<>();
            justification.put("label", "Explanation " + i++);

            List<Map<String, Object>> axiomEntries = new ArrayList<>();
            for (OWLAxiom axiom : explanation.getAxioms()) {
                long membershipCount = allAxiomSets.stream()
                        .filter(set -> set.contains(axiom))
                        .count();

                Map<String, Object> entry = new HashMap<>();
                entry.put("text", renderAxiom(axiom, ontology));
                entry.put("membershipNote",
                        membershipCount == allAxiomSets.size() ? "In ALL other justifications"
                        : membershipCount == 1 ? "In NO other justifications"
                        : "In " + (membershipCount - 1) + " other justifications");
                axiomEntries.add(entry);
            }
            justification.put("axioms", axiomEntries);
            justifications.add(justification);
        }
        return justifications;
    }

    private OWLOntology stripSwrlRules(OWLOntology ontology) {
        Set<OWLAxiom> axioms = ontology.getAxioms().stream()
                .filter(ax -> ax.getAxiomType() != AxiomType.SWRL_RULE)
                .collect(Collectors.toSet());
        try {
            OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
            return mgr.createOntology(axioms);
        } catch (OWLOntologyCreationException e) {
            log.warn("Failed to strip SWRL rules, falling back to original ontology", e);
            return ontology;
        }
    }

    private String renderAxiom(OWLAxiom axiom, OWLOntology ontology) {
        if (axiom instanceof OWLClassAssertionAxiom ax) {
            if (!ax.getIndividual().isAnonymous() && !ax.getClassExpression().isAnonymous()) {
                return label(ax.getIndividual().asOWLNamedIndividual(), ontology) + " Type "
                        + label(ax.getClassExpression().asOWLClass(), ontology);
            }
        } else if (axiom instanceof OWLSubClassOfAxiom ax) {
            if (!ax.getSubClass().isAnonymous() && !ax.getSuperClass().isAnonymous()) {
                return label(ax.getSubClass().asOWLClass(), ontology) + " SubClassOf "
                        + label(ax.getSuperClass().asOWLClass(), ontology);
            }
        } else if (axiom instanceof OWLDisjointClassesAxiom ax) {
            String names = ax.getClassesInSignature().stream()
                    .map(c -> label(c, ontology))
                    .collect(Collectors.joining(", "));
            return "DisjointClasses: " + names;
        } else if (axiom instanceof OWLDisjointUnionAxiom ax) {
            String disjuncts = ax.getClassExpressions().stream()
                    .filter(ce -> !ce.isAnonymous())
                    .map(ce -> label(ce.asOWLClass(), ontology))
                    .collect(Collectors.joining(", "));
            return label(ax.getOWLClass(), ontology) + " DisjointUnionOf " + disjuncts;
        } else if (axiom instanceof OWLEquivalentClassesAxiom ax) {
            String names = ax.getClassExpressions().stream()
                    .filter(ce -> !ce.isAnonymous())
                    .map(ce -> label(ce.asOWLClass(), ontology))
                    .collect(Collectors.joining(" ≡ "));
            return "EquivalentClasses: " + names;
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom ax) {
            if (!ax.getSubject().isAnonymous() && !ax.getObject().isAnonymous()) {
                return label(ax.getSubject().asOWLNamedIndividual(), ontology) + " "
                        + label(ax.getProperty().getNamedProperty(), ontology) + " "
                        + label(ax.getObject().asOWLNamedIndividual(), ontology);
            }
        } else if (axiom instanceof OWLDifferentIndividualsAxiom ax) {
            String names = ax.getIndividualsInSignature().stream()
                    .map(i -> label(i, ontology))
                    .collect(Collectors.joining(", "));
            return "DifferentIndividuals: " + names;
        } else if (axiom instanceof OWLSameIndividualAxiom ax) {
            String names = ax.getIndividualsInSignature().stream()
                    .map(i -> label(i, ontology))
                    .collect(Collectors.joining(" = "));
            return "SameIndividual: " + names;
        } else if (axiom instanceof OWLFunctionalObjectPropertyAxiom ax) {
            return "FunctionalObjectProperty: " + label(ax.getProperty().getNamedProperty(), ontology);
        } else if (axiom instanceof OWLInverseFunctionalObjectPropertyAxiom ax) {
            return "InverseFunctionalObjectProperty: " + label(ax.getProperty().getNamedProperty(), ontology);
        }

        String rendered = axiom.toString();
        for (OWLEntity entity : axiom.getSignature()) {
            String entityLabel = label(entity, ontology);
            String iri = entity.getIRI().toString();
            if (entityLabel != null && !entityLabel.isBlank() && !entityLabel.equals(iri)) {
                rendered = rendered.replace("<" + iri + ">", entityLabel);
            }
        }
        return rendered;
    }

    private Map<String, Object> executeClassify(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());
        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), type, job.getOwnerEmail())) {
            OWLReasoner reasoner = session.reasoner();
            ReasonerType effective = session.actualReasonerType() != null ? session.actualReasonerType() : type;

            if (!reasoner.isConsistent()) {
                return analyzeInconsistency(session.ontology());
            }

            long start = System.currentTimeMillis();
            precomputeHierarchy(reasoner, effective);
            long duration = System.currentTimeMillis() - start;
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("reasonerType", effective.getDisplayName());
            result.put("durationMs", duration);
            result.put("message", "Classification completed successfully");
            if (session.downgradedWarning() != null) {
                result.put("downgradedWarning", session.downgradedWarning());
            }
            return result;
        }
    }

    private Map<String, Object> analyzeInconsistency(OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        List<Map<String, Object>> issues = new ArrayList<>();

        for (OWLNamedIndividual ind : ontology.getIndividualsInSignature()) {
            List<OWLClassExpression> assertedTypes = EntitySearcher.getTypes(ind, ontology)
                    .collect(Collectors.toList());
            List<OWLClass> namedTypes = assertedTypes.stream()
                    .filter(t -> !t.isAnonymous())
                    .map(OWLClassExpression::asOWLClass)
                    .collect(Collectors.toList());

            for (OWLClass namedType : namedTypes) {
                if (assertedTypes.contains(df.getOWLObjectComplementOf(namedType))) {
                    Map<String, Object> issue = new HashMap<>();
                    issue.put("type", "complement_conflict");
                    issue.put("individual", label(ind, ontology));
                    issue.put("iri", ind.getIRI().toString());
                    issue.put("message", "\"" + label(ind, ontology) + "\" is assigned to both \""
                            + label(namedType, ontology) + "\" and its complement — these cannot both be true.");
                    issues.add(issue);
                    break;
                }
            }

            for (int i = 0; i < namedTypes.size(); i++) {
                OWLClass typeA = namedTypes.get(i);
                Set<OWLClass> disjointWithA = ontology.getDisjointClassesAxioms(typeA).stream()
                        .flatMap(ax -> ax.getClassExpressions().stream())
                        .filter(e -> !e.isAnonymous() && !e.equals(typeA))
                        .map(OWLClassExpression::asOWLClass)
                        .collect(Collectors.toSet());
                for (int j = i + 1; j < namedTypes.size(); j++) {
                    OWLClass typeB = namedTypes.get(j);
                    if (disjointWithA.contains(typeB)) {
                        Map<String, Object> issue = new HashMap<>();
                        issue.put("type", "disjoint_conflict");
                        issue.put("individual", label(ind, ontology));
                        issue.put("iri", ind.getIRI().toString());
                        issue.put("conflictingTypes", List.of(label(typeA, ontology), label(typeB, ontology)));
                        issue.put("message", "\"" + label(ind, ontology) + "\" belongs to both \""
                                + label(typeA, ontology) + "\" and \"" + label(typeB, ontology)
                                + "\" — but these classes are declared disjoint.");
                        issues.add(issue);
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("inconsistent", true);
        result.put("issues", issues);
        String summary = issues.isEmpty()
                ? "The ontology is logically inconsistent. The conflict may involve property restrictions or complex class expressions. Check your disjoint constraints and complement definitions."
                : "Found " + issues.size() + " inconsistenc" + (issues.size() == 1 ? "y" : "ies") + " — see details below and fix them in the editor.";
        result.put("message", summary);
        return result;
    }

    private Map<String, Object> executeRealize(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());
        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), type, job.getOwnerEmail())) {
            OWLReasoner reasoner = session.reasoner();
            ReasonerType effective = session.actualReasonerType() != null ? session.actualReasonerType() : type;
            long start = System.currentTimeMillis();
            reasoner.precomputeInferences(org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);
            long duration = System.currentTimeMillis() - start;
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("reasonerType", effective.getDisplayName());
            result.put("durationMs", duration);
            result.put("message", "Realization completed successfully");
            if (session.downgradedWarning() != null) {
                result.put("downgradedWarning", session.downgradedWarning());
            }
            return result;
        }
    }

    private Map<String, Object> executeFullRun(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());
        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), type, job.getOwnerEmail())) {
            OWLReasoner reasoner = session.reasoner();
            ReasonerType effective = session.actualReasonerType() != null ? session.actualReasonerType() : type;
            Map<String, Object> result = new HashMap<>();
            long totalStart = System.currentTimeMillis();

            if (session.downgradedWarning() != null) {
                result.put("downgradedWarning", session.downgradedWarning());
            }

            long t0 = System.currentTimeMillis();
            boolean consistent = reasoner.isConsistent();
            result.put("consistencyCheckMs", System.currentTimeMillis() - t0);
            result.put("consistent", consistent);
            if (!consistent) {
                result.put("success", false);
                result.put("message", "Ontology is inconsistent.");
                return result;
            }

            t0 = System.currentTimeMillis();
            precomputeHierarchy(reasoner, effective);
            result.put("classificationMs", System.currentTimeMillis() - t0);

            t0 = System.currentTimeMillis();
            reasoner.precomputeInferences(org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);
            result.put("realizationMs", System.currentTimeMillis() - t0);

            result.put("totalDurationMs", System.currentTimeMillis() - totalStart);
            result.put("reasonerType", effective.getDisplayName());
            result.put("success", true);
            result.put("message", "Reasoning completed successfully");
            return result;
        }
    }

    private Map<String, Object> executeInferredAxioms(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());
        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), type, job.getOwnerEmail())) {
            OWLOntology ontology = session.ontology();
            OWLReasoner reasoner = session.reasoner();
            ReasonerType effective = session.actualReasonerType() != null ? session.actualReasonerType() : type;

            if (!reasoner.isConsistent()) {
                return analyzeInconsistency(ontology);
            }

            long start = System.currentTimeMillis();
            precomputeHierarchy(reasoner, effective);
            reasoner.precomputeInferences(org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);

            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            Set<OWLAxiom> inferredAxioms = new LinkedHashSet<>();
            for (OWLClass owlClass : ontology.getClassesInSignature()) {
                if (owlClass.isOWLThing() || owlClass.isOWLNothing()) {
                    continue;
                }
                for (OWLClass superClass : reasoner.getSuperClasses(owlClass, false).getFlattened()) {
                    if (!superClass.isOWLThing()) {
                        inferredAxioms.add(df.getOWLSubClassOfAxiom(owlClass, superClass));
                    }
                }
                for (OWLNamedIndividual individual : reasoner.getInstances(owlClass, false).getFlattened()) {
                    inferredAxioms.add(df.getOWLClassAssertionAxiom(owlClass, individual));
                }
            }
            long duration = System.currentTimeMillis() - start;

            List<Map<String, String>> axiomsList = inferredAxioms.stream()
                    .limit(100)
                    .map(axiom -> Map.of(
                            "axiomType", axiom.getAxiomType().getName(),
                            "axiom", axiom.toString(),
                            "readable", formatAxiom(axiom, ontology)
                    ))
                    .collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("reasonerType", effective.getDisplayName());
            result.put("durationMs", duration);
            result.put("totalInferredAxioms", inferredAxioms.size());
            result.put("axioms", axiomsList);
            result.put("message", axiomsList.size() < inferredAxioms.size()
                    ? "Showing first 100 of " + inferredAxioms.size() + " inferred axioms"
                    : "Showing all " + inferredAxioms.size() + " inferred axioms");
            if (session.downgradedWarning() != null) {
                result.put("downgradedWarning", session.downgradedWarning());
            }
            return result;
        }
    }

    private String formatAxiom(OWLAxiom axiom, OWLOntology ontology) {
        String axiomString = axiom.toString();
        for (OWLEntity entity : axiom.getSignature()) {
            axiomString = axiomString.replace(entity.getIRI().toString(), label(entity, ontology));
        }
        return axiomString;
    }

    private void precomputeHierarchy(OWLReasoner reasoner, ReasonerType type) {
        if (type == ReasonerType.ELK) {
            reasoner.precomputeInferences(org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY);
        } else {
            reasoner.precomputeInferences(
                    org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY,
                    org.semanticweb.owlapi.reasoner.InferenceType.OBJECT_PROPERTY_HIERARCHY,
                    org.semanticweb.owlapi.reasoner.InferenceType.DATA_PROPERTY_HIERARCHY
            );
        }
    }

    private ReasonerType parseReasonerType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReasonerType.OPENLLET;
        }
        try {
            return ReasonerType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown reasoner type '{}', falling back to OPENLLET", raw);
            return ReasonerType.OPENLLET;
        }
    }

    private OWLClassExpression parseClassExpression(OWLOntology ontology, String expression) {
        try {
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            ShortFormProvider shortFormProvider = new SimpleShortFormProvider();
            BidirectionalShortFormProvider bidi = new BidirectionalShortFormProviderAdapter(
                    manager, ontology.getImportsClosure(), shortFormProvider);
            OWLEntityChecker checker = new ShortFormEntityChecker(bidi);
            ManchesterOWLSyntaxParser parser = org.semanticweb.owlapi.apibinding.OWLManager.createManchesterParser();
            parser.setOWLEntityChecker(checker);
            parser.setDefaultOntology(ontology);
            parser.setStringToParse(expression);
            return parser.parseClassExpression();
        } catch (Exception e) {
            return findClassByName(ontology, expression);
        }
    }

    private OWLClass findClassByName(OWLOntology ontology, String name) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        if (name.startsWith("http://") || name.startsWith("https://")) {
            return df.getOWLClass(IRI.create(name));
        }
        for (OWLClass cls : ontology.getClassesInSignature(true)) {
            if (cls.getIRI().getShortForm().equalsIgnoreCase(name)) {
                return cls;
            }
        }
        return null;
    }

    private List<Map<String, Object>> superClasses(OWLReasoner reasoner, OWLClassExpression expr,
                                                   OWLOntology ontology, boolean direct) {
        return reasoner.getSuperClasses(expr, direct).getFlattened().stream()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .map(c -> item("class", c, ontology))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> subClasses(OWLReasoner reasoner, OWLClassExpression expr,
                                                 OWLOntology ontology, boolean direct) {
        return reasoner.getSubClasses(expr, direct).getFlattened().stream()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .map(c -> item("class", c, ontology))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> equivalentClasses(OWLReasoner reasoner, OWLClassExpression expr,
                                                        OWLOntology ontology) {
        return reasoner.getEquivalentClasses(expr).getEntities().stream()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .map(c -> item("class", c, ontology))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> instances(OWLReasoner reasoner, OWLClassExpression expr,
                                                OWLOntology ontology, boolean direct) {
        return reasoner.getInstances(expr, direct).getFlattened().stream()
                .map(i -> item("individual", i, ontology))
                .collect(Collectors.toList());
    }

    private Map<String, Object> item(String type, OWLEntity entity, OWLOntology ontology) {
        return Map.of("type", type, "iri", entity.getIRI().toString(), "label", label(entity, ontology));
    }

    private String label(OWLEntity entity, OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        for (OWLAnnotation ann : EntitySearcher.getAnnotations(entity, ontology, df.getRDFSLabel())
                .collect(Collectors.toList())) {
            if (ann.getValue() instanceof OWLLiteral literal) {
                return literal.getLiteral();
            }
        }
        return entity.getIRI().getShortForm();
    }

    private static final int INITIAL_HIERARCHY_DEPTH = 3;

    private Map<String, Object> executeHierarchy(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());

        if (type == ReasonerType.HERMIT) type = ReasonerType.OPENLLET;
        try (OntologySessionService.ReasoningSession session =
                     sessionService.openSession(job.getProjectId(), type, job.getOwnerEmail())) {
            OWLReasoner reasoner = session.reasoner();
            ReasonerType effective = session.actualReasonerType() != null ? session.actualReasonerType() : type;
            OWLOntology ontology = session.ontology();
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            precomputeHierarchy(reasoner, effective);

            OWLClass thing = df.getOWLThing();
            OWLClass nothing = df.getOWLNothing();
            Set<String> visited = new HashSet<>();
            Map<String, Object> root = buildClassNode(ontology, reasoner, thing, visited, INITIAL_HIERARCHY_DEPTH);
            List<Map<String, Object>> hierarchy = new ArrayList<>();
            hierarchy.add(root);
            if (reasoner.getUnsatisfiableClasses().getSize() > 1
                    || !reasoner.getSubClasses(nothing, true).isEmpty()) {
                hierarchy.add(buildClassNode(ontology, reasoner, nothing, visited, INITIAL_HIERARCHY_DEPTH));
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("hierarchy", hierarchy);
            result.put("reasonerType", effective.getDisplayName());
            result.put("totalClasses", visited.size());
            if (session.downgradedWarning() != null) result.put("downgradedWarning", session.downgradedWarning());
            return result;
        }
    }

    private Map<String, Object> executeObjPropHierarchy(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());

        if (type == ReasonerType.ELK || type == ReasonerType.HERMIT) type = ReasonerType.OPENLLET;
        try (OntologySessionService.ReasoningSession session =
                     sessionService.openSession(job.getProjectId(), type, job.getOwnerEmail())) {
            OWLReasoner reasoner = session.reasoner();
            ReasonerType effective = session.actualReasonerType() != null ? session.actualReasonerType() : type;
            OWLOntology ontology = session.ontology();
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLObjectProperty topProp = df.getOWLTopObjectProperty();

            precomputeHierarchy(reasoner, effective);

            Set<String> visited = new HashSet<>();
            Map<String, Object> root;
            try {
                root = buildObjectPropertyNode(ontology, reasoner, topProp, visited);
            } catch (UnsupportedOperationException e) {
                root = Map.of("id", topProp.getIRI().toString(), "label", "owl:topObjectProperty",
                        "children", List.of(), "hasChildren", false, "type", "ObjectProperty");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
            if (children.isEmpty()) {
                List<Map<String, Object>> asserted = ontology.getObjectPropertiesInSignature().stream()
                        .filter(p -> !p.isOWLTopObjectProperty() && !p.isOWLBottomObjectProperty())
                        .map(p -> Map.<String, Object>of("id", p.getIRI().toString(), "label", label(p, ontology),
                                "children", List.of(), "hasChildren", false, "type", "ObjectProperty"))
                        .collect(Collectors.toList());
                root = new HashMap<>(root);
                root.put("children", asserted);
                root.put("hasChildren", !asserted.isEmpty());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("hierarchy", List.of(root));
            result.put("reasonerType", effective.getDisplayName());
            if (session.downgradedWarning() != null) result.put("downgradedWarning", session.downgradedWarning());
            return result;
        }
    }

    private Map<String, Object> executeDataPropHierarchy(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());
        if (type == ReasonerType.ELK || type == ReasonerType.HERMIT) type = ReasonerType.OPENLLET;
        try (OntologySessionService.ReasoningSession session =
                     sessionService.openSession(job.getProjectId(), type, job.getOwnerEmail())) {
            OWLReasoner reasoner = session.reasoner();
            ReasonerType effective = session.actualReasonerType() != null ? session.actualReasonerType() : type;
            OWLOntology ontology = session.ontology();
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLDataProperty topProp = df.getOWLTopDataProperty();

            precomputeHierarchy(reasoner, effective);

            Set<String> visited = new HashSet<>();
            Map<String, Object> root;
            try {
                root = buildDataPropertyNode(ontology, reasoner, topProp, visited);
            } catch (UnsupportedOperationException e) {
                root = Map.of("id", topProp.getIRI().toString(), "label", "owl:topDataProperty",
                        "children", List.of(), "hasChildren", false, "type", "DataProperty");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
            if (children.isEmpty()) {
                List<Map<String, Object>> asserted = ontology.getDataPropertiesInSignature().stream()
                        .filter(p -> !p.isOWLTopDataProperty() && !p.isOWLBottomDataProperty())
                        .map(p -> Map.<String, Object>of("id", p.getIRI().toString(), "label", label(p, ontology),
                                "children", List.of(), "hasChildren", false, "type", "DataProperty"))
                        .collect(Collectors.toList());
                root = new HashMap<>(root);
                root.put("children", asserted);
                root.put("hasChildren", !asserted.isEmpty());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("hierarchy", List.of(root));
            result.put("reasonerType", effective.getDisplayName());
            if (session.downgradedWarning() != null) result.put("downgradedWarning", session.downgradedWarning());
            return result;
        }
    }

    private Map<String, Object> buildClassNode(OWLOntology ontology, OWLReasoner reasoner,
                                               OWLClass owlClass, Set<String> visited, int maxDepth) {
        String iri = owlClass.getIRI().toString();
        List<Map<String, String>> equivalentClasses = reasoner.getEquivalentClasses(owlClass).getEntities().stream()
                .filter(cls -> !cls.equals(owlClass))
                .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", label(cls, ontology)))
                .collect(Collectors.toList());

        if (visited.contains(iri) && !owlClass.isOWLThing() && !owlClass.isOWLNothing()) {
            return Map.of("id", iri, "label", label(owlClass, ontology),
                    "children", List.of(), "hasChildren", false, "equivalentClasses", equivalentClasses);
        }
        visited.add(iri);

        org.semanticweb.owlapi.reasoner.NodeSet<OWLClass> subClassesNodeSet =
                reasoner.getSubClasses(owlClass, true);
        boolean hasAnyChildren = subClassesNodeSet.getFlattened().stream()
                .anyMatch(c -> !c.isOWLNothing() && !c.equals(owlClass));

        List<Map<String, Object>> children = new ArrayList<>();
        if (maxDepth > 0) {
            for (org.semanticweb.owlapi.reasoner.Node<OWLClass> subClassNode : subClassesNodeSet) {
                OWLClass representative = subClassNode.getRepresentativeElement();
                if (representative.isOWLNothing() && !owlClass.isOWLThing()) continue;
                if (representative.equals(owlClass)) continue;
                children.add(buildClassNode(ontology, reasoner, representative, visited, maxDepth - 1));
            }
            children.sort(Comparator.comparing(m -> m.get("label").toString()));
        }

        Map<String, Object> node = new HashMap<>();
        node.put("id", iri);
        node.put("label", label(owlClass, ontology));
        node.put("children", children);
        node.put("hasChildren", hasAnyChildren);
        node.put("type", "Class");
        node.put("equivalentClasses", equivalentClasses);
        if (owlClass.isOWLNothing() || !reasoner.isSatisfiable(owlClass)) {
            node.put("isUnsatisfiable", true);
        }
        return node;
    }

    private Map<String, Object> buildObjectPropertyNode(OWLOntology ontology, OWLReasoner reasoner,
                                                        OWLObjectProperty property, Set<String> visited) {
        String iri = property.getIRI().toString();
        List<Map<String, String>> equivalentProperties =
                reasoner.getEquivalentObjectProperties(property).getEntities().stream()
                        .filter(p -> !p.equals(property) && !p.isAnonymous())
                        .map(p -> Map.of("iri", p.asOWLObjectProperty().getIRI().toString(),
                                "label", label(p.asOWLObjectProperty(), ontology)))
                        .collect(Collectors.toList());

        if (visited.contains(iri) && !property.isOWLTopObjectProperty()) {
            return Map.of("id", iri, "label", label(property, ontology),
                    "children", List.of(), "hasChildren", false, "equivalentProperties", equivalentProperties);
        }
        visited.add(iri);

        List<Map<String, Object>> children = new ArrayList<>();
        for (org.semanticweb.owlapi.reasoner.Node<OWLObjectPropertyExpression> node :
                reasoner.getSubObjectProperties(property, true)) {
            OWLObjectPropertyExpression rep = node.getRepresentativeElement();
            if (rep.isAnonymous()) continue;
            OWLObjectProperty sub = rep.asOWLObjectProperty();
            if (sub.isOWLBottomObjectProperty() || sub.equals(property)) continue;
            children.add(buildObjectPropertyNode(ontology, reasoner, sub, visited));
        }
        children.sort(Comparator.comparing(m -> m.get("label").toString()));

        Map<String, Object> result = new HashMap<>();
        result.put("id", iri);
        result.put("label", label(property, ontology));
        result.put("children", children);
        result.put("hasChildren", !children.isEmpty());
        result.put("type", "ObjectProperty");
        result.put("equivalentProperties", equivalentProperties);
        return result;
    }

    private Map<String, Object> buildDataPropertyNode(OWLOntology ontology, OWLReasoner reasoner,
                                                      OWLDataProperty property, Set<String> visited) {
        String iri = property.getIRI().toString();
        if (visited.contains(iri) && !property.isOWLTopDataProperty()) {
            return Map.of("id", iri, "label", label(property, ontology),
                    "children", List.of(), "hasChildren", false, "type", "DataProperty");
        }
        visited.add(iri);

        List<Map<String, Object>> children = new ArrayList<>();
        for (org.semanticweb.owlapi.reasoner.Node<OWLDataProperty> node :
                reasoner.getSubDataProperties(property, true)) {
            OWLDataProperty sub = node.getRepresentativeElement();
            if (sub.isOWLBottomDataProperty() || sub.equals(property)) continue;
            children.add(buildDataPropertyNode(ontology, reasoner, sub, visited));
        }
        children.sort(Comparator.comparing(m -> m.get("label").toString()));

        Map<String, Object> result = new HashMap<>();
        result.put("id", iri);
        result.put("label", label(property, ontology));
        result.put("children", children);
        result.put("hasChildren", !children.isEmpty());
        result.put("type", "DataProperty");
        return result;
    }
}
