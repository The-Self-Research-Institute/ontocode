package self.research.ontology.reasoner.service;

import org.semanticweb.owl.explanation.api.Explanation;
import org.semanticweb.owl.explanation.api.ExplanationGenerator;
import org.semanticweb.owl.explanation.api.ExplanationGeneratorFactory;
import org.semanticweb.owl.explanation.api.ExplanationProgressMonitor;
import org.semanticweb.owl.explanation.impl.blackbox.Configuration;
import org.semanticweb.owl.explanation.impl.blackbox.ContractionStrategy;
import org.semanticweb.owl.explanation.impl.blackbox.DivideAndConquerContractionStrategy;
import org.semanticweb.owl.explanation.impl.blackbox.EntailmentCheckerFactory;
import org.semanticweb.owl.explanation.impl.blackbox.ExpansionStrategy;
import org.semanticweb.owl.explanation.impl.blackbox.InitialEntailmentCheckStrategy;
import org.semanticweb.owl.explanation.impl.blackbox.StructuralTypePriorityExpansionStrategy;
import org.semanticweb.owl.explanation.impl.blackbox.checker.BlackBoxExplanationGeneratorFactory;
import org.semanticweb.owl.explanation.impl.blackbox.checker.InconsistentOntologyExplanationGeneratorFactory;
import org.semanticweb.owl.explanation.impl.blackbox.checker.SatisfiabilityEntailmentCheckerFactory;
import org.semanticweb.owl.explanation.impl.laconic.LaconicExplanationGeneratorFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.expression.ShortFormEntityChecker;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import org.semanticweb.owlapi.reasoner.TimeOutException;
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
import self.research.ontology.common.EntailmentAxiomBuilder;
import self.research.ontology.common.JustificationRendering;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class ReasoningJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReasoningJobExecutor.class);

    private final OntologySessionService sessionService;
    private final ReasoningSnapshotService snapshotService;
    private final OWLReasonerFactory dlReasonerFactory;

    private static final OWLDataFactory EXPLANATION_DATA_FACTORY =
            OWLManager.createOWLOntologyManager().getOWLDataFactory();

    @Value("${ontocode.reasoner.justification-timeout-ms:15000}")
    private long justificationTimeoutMs;

    @Value("${ontocode.reasoning.job-timeout-ms:1800000}")
    private long jobTimeoutMs;

    @Value("${ontocode.reasoner.max-justifications:5}")
    private int defaultMaxJustifications;

    public ReasoningJobExecutor(OntologySessionService sessionService, ReasoningSnapshotService snapshotService) {
        this.sessionService = sessionService;
        this.snapshotService = snapshotService;
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
            case REASONER_EXPLAIN_ENTAILMENT -> executeExplainEntailment(job);
            case REASONER_SNAPSHOT -> snapshotService.snapshot(job.getProjectId(), job.getReasonerType(), job.getOwnerEmail());
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
                Map<String, Object> parseFailure = new HashMap<>();
                parseFailure.put("success", false);
                parseFailure.put("error", "Failed to parse class expression: " + job.getExpression());
                parseFailure.put("hint", "Check Manchester OWL syntax. Examples: 'Person', 'Person and hasAge some integer'");
                attachSessionMetadata(parseFailure, session);
                return parseFailure;
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
            attachSessionMetadata(response, session);
            return response;
        }
    }

    private Map<String, Object> executeConsistency(ReasoningJob job) throws Exception {
        ReasonerType type = parseReasonerType(job.getReasonerType());
        if (!hasOwner(job)) {
            Optional<Map<String, Object>> snapshot =
                    snapshotService.cachedOrInFlight(job.getProjectId(), job.getReasonerType());
            if (snapshot.isPresent()) {
                return consistencyFromSnapshot(job, snapshot.get());
            }
        }
        try (OntologySessionService.ReasoningSession session = sessionService.openSession(job.getProjectId(), type, job.getOwnerEmail())) {
            OWLReasoner reasoner = session.reasoner();
            ReasonerType effective = session.actualReasonerType() != null ? session.actualReasonerType() : type;
            boolean consistent = reasoner.isConsistent();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("consistent", consistent);
            result.put("reasonerType", effective.getDisplayName());
            result.put("projectId", job.getProjectId());
            attachSessionMetadata(result, session);
            if (!consistent) {
                List<Map<String, String>> unsatisfiable = new ArrayList<>();
                try {
                    Set<OWLClass> unsat = new HashSet<>(reasoner.getUnsatisfiableClasses().getEntities());
                    OWLDataFactory df = session.ontology().getOWLOntologyManager().getOWLDataFactory();
                    unsat.remove(df.getOWLNothing());
                    unsatisfiable = unsat.stream()
                            .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", label(cls, session.ontology())))
                            .collect(Collectors.toList());
                } catch (Exception e) {
                    rethrowIfInterrupted(e);
                }
                result.put("unsatisfiableClasses", unsatisfiable);
            }
            return result;
        }
    }

    private Map<String, Object> consistencyFromSnapshot(ReasoningJob job, Map<String, Object> snapshot) {
        boolean consistent = Boolean.TRUE.equals(snapshot.get("consistent"));
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("consistent", consistent);
        result.put("reasonerType", snapshot.get("reasonerType"));
        result.put("projectId", job.getProjectId());
        attachSnapshotMetadata(result, snapshot);
        if (!consistent) {
            result.put("unsatisfiableClasses", snapshot.get("unsatisfiableClasses"));
        }
        return result;
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

            boolean isConsistent = session.reasoner().isConsistent();

            explanation.put("success", true);
            explanation.put("isConsistent", isConsistent);
            explanation.put("usedReasoner", effective.getDisplayName());
            if (requestedType != effective) {
                explanation.put("reasonerUpgraded", true);
            }
            attachSessionMetadata(explanation, session);

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
                rethrowIfInterrupted(e);
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
                rethrowIfInterrupted(e);
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
                rethrowIfInterrupted(e);
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

    private Map<String, Object> executeExplainEntailment(ReasoningJob job) {
        ReasonerType requestedType = parseReasonerType(job.getReasonerType());

        Map<String, Object> result = new HashMap<>();
        try {
           
            OWLOntology ontology = sessionService.loadForParse(job.getProjectId(), job.getOwnerEmail());
            int maxJustifications = job.getMaxJustifications() != null ? job.getMaxJustifications() : defaultMaxJustifications;
            String mode = job.getExplanationMode() != null && !job.getExplanationMode().isBlank()
                    ? job.getExplanationMode() : "regular";

            OWLAxiom entailment = EntailmentAxiomBuilder.buildEntailmentAxiom(job.getEntailmentType(), job.getSubjectIri(),
                    job.getTargetIri(), job.getPropertyIri(), job.getLiteralValue());
            List<Map<String, Object>> justifications = findEntailmentJustifications(ontology, entailment, maxJustifications, mode);

            result.put("success", true);
            result.put("usedReasoner", "HermiT");
            if (requestedType != ReasonerType.HERMIT) {
                result.put("reasonerUpgraded", true);
            }
            result.put("justifications", justifications);
            long revision = sessionService.currentRevision(job.getProjectId());
            if (revision >= 0) {
                result.put("ontologyRevision", revision);
            }
            return result;
        } catch (Exception e) {
            rethrowIfInterrupted(e);
            log.error("Error explaining entailment for project {}", job.getProjectId(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    private List<Map<String, Object>> findEntailmentJustifications(
            OWLOntology ontology, OWLAxiom entailment, int limit, String mode) {
        OWLOntology reasoningOntology = JustificationRendering.stripSwrlRules(ontology);
        Supplier<OWLOntologyManager> ontologyManagerSupplier = OWLManager::createOWLOntologyManager;

        EntailmentCheckerFactory<OWLAxiom> checkerFactory =
                new SatisfiabilityEntailmentCheckerFactory(new LenientHermitReasonerFactory(), ontologyManagerSupplier);
        ExpansionStrategy<OWLAxiom> expansionStrategy =
                new StructuralTypePriorityExpansionStrategy<>(InitialEntailmentCheckStrategy.PERFORM, ontologyManagerSupplier);
        ContractionStrategy<OWLAxiom> contractionStrategy = new DivideAndConquerContractionStrategy<>();
        Configuration<OWLAxiom> configuration =
                new Configuration<>(checkerFactory, expansionStrategy, contractionStrategy, ontologyManagerSupplier);
        ExplanationGeneratorFactory<OWLAxiom> baseFactory = new BlackBoxExplanationGeneratorFactory<>(configuration);

        ExplanationGeneratorFactory<OWLAxiom> factory = "laconic".equals(mode)
                ? new LaconicExplanationGeneratorFactory<>(baseFactory, ontologyManagerSupplier)
                : baseFactory;

        ExplanationGenerator<OWLAxiom> generator =
                factory.createExplanationGenerator(reasoningOntology, new StopWhenThreadInterrupted());
        Set<Explanation<OWLAxiom>> explanations = generator.getExplanations(entailment, limit);
        return renderJustifications(explanations, ontology);
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
        OWLOntology reasoningOntology = JustificationRendering.stripSwrlRules(ontology);
        InconsistentOntologyExplanationGeneratorFactory factory =
                new InconsistentOntologyExplanationGeneratorFactory(
                        new LenientHermitReasonerFactory(), EXPLANATION_DATA_FACTORY, OWLManager::createOWLOntologyManager, justificationTimeoutMs);
        ExplanationGenerator<OWLAxiom> generator =
                factory.createExplanationGenerator(reasoningOntology, new StopWhenThreadInterrupted());
        OWLAxiom entailment = EXPLANATION_DATA_FACTORY.getOWLSubClassOfAxiom(
                EXPLANATION_DATA_FACTORY.getOWLThing(), EXPLANATION_DATA_FACTORY.getOWLNothing());
        Set<Explanation<OWLAxiom>> explanations = generator.getExplanations(entailment, limit);
        return renderJustifications(explanations, ontology);
    }

    private List<Map<String, Object>> findLaconicJustifications(OWLOntology ontology, int limit) {
        OWLOntology reasoningOntology = JustificationRendering.stripSwrlRules(ontology);

        InconsistentOntologyExplanationGeneratorFactory baseFactory =
                new InconsistentOntologyExplanationGeneratorFactory(
                        new LenientHermitReasonerFactory(), EXPLANATION_DATA_FACTORY, OWLManager::createOWLOntologyManager, justificationTimeoutMs);

        ExplanationGeneratorFactory<OWLAxiom> laconicFactory =
                new LaconicExplanationGeneratorFactory<OWLAxiom>(baseFactory, OWLManager::createOWLOntologyManager);

        ExplanationGenerator<OWLAxiom> generator =
                laconicFactory.createExplanationGenerator(reasoningOntology, new StopWhenThreadInterrupted());
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
                entry.put("text", JustificationRendering.renderAxiom(axiom, ontology, this::label));
                entry.put("membershipNote",
                        membershipCount == allAxiomSets.size() ? "In ALL other justifications"
                        : membershipCount == 1 ? "In NO other justifications"
                        : "In " + (membershipCount - 1) + " other justifications");
                entry.put("entities", JustificationRendering.extractEntities(axiom, ontology, this::label));
                entry.put("role", JustificationRendering.isConflictAxiom(axiom) ? "CONFLICT" : "CONTRIBUTING");
                axiomEntries.add(entry);
            }
            justification.put("axioms", axiomEntries);
            justifications.add(justification);
        }
        return justifications;
    }

    private Map<String, Object> executeClassify(ReasoningJob job) throws Exception {
        Map<String, Object> snapshot = snapshotFor(job);
        if (!isConsistent(snapshot)) {
            return inconsistencyResult(snapshot);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("reasonerType", snapshot.get("reasonerType"));
        result.put("durationMs", snapshot.get("durationMs"));
        result.put("message", "Classification completed successfully");
        result.put("classHierarchy", snapshot.get("classHierarchyFlat"));
        result.put("equivalentClasses", snapshot.get("equivalentClasses"));
        result.put("unsatisfiableClasses", snapshot.get("unsatisfiableClasses"));
        result.put("objectPropertyHierarchy", snapshot.get("objectPropertyHierarchyFlat"));
        result.put("dataPropertyHierarchy", snapshot.get("dataPropertyHierarchyFlat"));
        result.put("totalClasses", snapshot.get("totalClasses"));
        result.put("stats", snapshot.get("stats"));
        attachSnapshotMetadata(result, snapshot);
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
            attachSessionMetadata(result, session);
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

            attachSessionMetadata(result, session);

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
        Map<String, Object> snapshot = snapshotFor(job);
        if (!isConsistent(snapshot)) {
            return inconsistencyResult(snapshot);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> inferred = (Map<String, Object>) snapshot.get("inferredAxioms");
        if (inferred == null || inferred.containsKey("error")) {
            throw new IllegalStateException(inferred != null && inferred.get("error") != null
                    ? inferred.get("error").toString() : "Inferred axioms are not available");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("reasonerType", snapshot.get("reasonerType"));
        result.put("durationMs", inferred.get("durationMs"));
        result.put("totalInferredAxioms", inferred.get("totalInferredAxioms"));
        result.put("axioms", inferred.get("axioms"));
        if (Boolean.TRUE.equals(inferred.get("totalIsPartial"))) {
            result.put("totalIsPartial", true);
        }
        result.put("message", inferred.get("message"));
        attachSnapshotMetadata(result, snapshot);
        return result;
    }

    private Map<String, Object> snapshotFor(ReasoningJob job) throws Exception {
        return snapshotService.snapshot(job.getProjectId(), job.getReasonerType(), job.getOwnerEmail());
    }

    private static boolean isConsistent(Map<String, Object> snapshot) {
        return Boolean.TRUE.equals(snapshot.get("consistent"));
    }

    private static void requireConsistent(Map<String, Object> snapshot) {
        if (!isConsistent(snapshot)) {
            throw new InconsistentOntologyException("Cannot do reasoning with inconsistent ontologies");
        }
    }

    private static Map<String, Object> inconsistencyResult(Map<String, Object> snapshot) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inconsistency = (Map<String, Object>) snapshot.get("inconsistency");
        Map<String, Object> result = new HashMap<>(inconsistency);
        attachSnapshotMetadata(result, snapshot);
        return result;
    }

    private static void attachSnapshotMetadata(Map<String, Object> result, Map<String, Object> snapshot) {
        if (snapshot.get("downgradedWarning") != null) {
            result.put("downgradedWarning", snapshot.get("downgradedWarning"));
        }
        if (snapshot.get("ontologyRevision") instanceof Number revision && revision.longValue() >= 0) {
            result.put("ontologyRevision", revision.longValue());
        }
    }

    private static boolean hasOwner(ReasoningJob job) {
        return job.getOwnerEmail() != null && !job.getOwnerEmail().isBlank();
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

    private void attachSessionMetadata(Map<String, Object> result, OntologySessionService.ReasoningSession session) {
        if (session.downgradedWarning() != null) {
            result.put("downgradedWarning", session.downgradedWarning());
        }
        if (session.revision() >= 0) {
            result.put("ontologyRevision", session.revision());
        }
    }

    private static void rethrowIfInterrupted(Exception e) {
        if (e instanceof ReasonerInterruptedException || e instanceof TimeOutException) {
            throw (RuntimeException) e;
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new ReasonerInterruptedException(e);
        }
    }

    private static final class StopWhenThreadInterrupted implements ExplanationProgressMonitor<OWLAxiom> {
        @Override
        public void foundExplanation(ExplanationGenerator<OWLAxiom> generator, Explanation<OWLAxiom> explanation,
                                     Set<Explanation<OWLAxiom>> allFoundExplanations) {
        }

        @Override
        public boolean isCancelled() {
            return Thread.currentThread().isInterrupted();
        }
    }

    private ReasonerType parseReasonerType(String raw) {
        return ReasonerType.fromRequest(raw);
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

    private Map<String, Object> executeHierarchy(ReasoningJob job) throws Exception {
        Map<String, Object> snapshot = snapshotFor(job);
        requireConsistent(snapshot);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("hierarchy", snapshot.get("classTree"));
        result.put("reasonerType", snapshot.get("reasonerType"));
        result.put("totalClasses", snapshot.get("classTreeVisited"));
        attachSnapshotMetadata(result, snapshot);
        return result;
    }

    private Map<String, Object> executeObjPropHierarchy(ReasoningJob job) throws Exception {
        return propertyHierarchy(snapshotFor(job), "objectPropertyTree");
    }

    private Map<String, Object> executeDataPropHierarchy(ReasoningJob job) throws Exception {
        return propertyHierarchy(snapshotFor(job), "dataPropertyTree");
    }

    private static Map<String, Object> propertyHierarchy(Map<String, Object> snapshot, String treeKey) {
        requireConsistent(snapshot);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("hierarchy", snapshot.get(treeKey));
        result.put("reasonerType", snapshot.get("propertyReasonerType"));
        attachSnapshotMetadata(result, snapshot);
        return result;
    }
}
