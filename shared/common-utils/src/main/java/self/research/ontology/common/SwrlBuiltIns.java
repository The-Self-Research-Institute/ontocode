package self.research.ontology.common;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLBuiltInAtom;
import org.semanticweb.owlapi.model.SWRLRule;

import org.semanticweb.owlapi.reasoner.OWLReasoner;
import self.research.ontology.common.rules.RuleExecution;
import self.research.ontology.common.rules.RuleExecutionLimits;
import self.research.ontology.common.rules.RuleExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SwrlBuiltIns {

    private SwrlBuiltIns() {
    }

    public static long countBuiltInRules(OWLOntology ontology) {
        return ontology.axioms(AxiomType.SWRL_RULE).filter(SwrlBuiltIns::usesBuiltIn).count();
    }

    public static OWLOntology withoutBuiltInRules(OWLOntology ontology) {
        return withoutBuiltInRules(ontology, RestRuleStage.shared());
    }

    public static OWLOntology withoutBuiltInRules(OWLOntology ontology, RuleStage stage) {
        return prepareForHermit(ontology, stage).forHermit();
    }

    public static String noteFor(OWLOntology ontology, RuleStage stage) {
        return prepareForHermit(ontology, stage).note();
    }

    public static PreparedOntology prepareChained(OWLOntology ontology, RuleStage stage,
                                                  Function<OWLOntology, OWLReasoner> reasonerFactory, String reasonerName,
                                                  String snapshotId) {
        if (ontology.importsClosure().noneMatch(o -> o.axioms(AxiomType.SWRL_RULE).findAny().isPresent())) {
            return new PreparedOntology(ontology, 0, 0, 0, null);
        }
        RuleExecutionResult result = new RuleExecution(stage, reasonerFactory, reasonerName)
                .run(ontology, snapshotId, RuleExecutionLimits.defaults());
        int skipped = result.skippedRules().size();
        List<String> parts = new ArrayList<>();
        if (!result.complete()) {
            parts.add(result.status().label() + ". " + result.message());
        } else if (result.engineRules() > 0 || result.translatedRules() > 0) {
            List<String> used = new ArrayList<>();
            if (result.translatedRules() > 0) {
                used.add(plural(result.translatedRules()) + " turned into OWL");
            }
            if (result.engineRules() > 0) {
                used.add(plural(result.engineRules()) + " applied with the rule engine");
            }
            parts.add("SWRL built-in functions are not supported by HermiT directly: " + String.join(" and ", used) + ".");
        }
        parts.addAll(result.limitations());
        String note = parts.isEmpty() ? null : String.join(" ", parts);
        return new PreparedOntology(result.working() != null ? result.working() : ontology, 0, result.executedRules().size(), skipped, note, result);
    }

    public static PreparedOntology prepareForHermit(OWLOntology ontology, RuleStage stage) {
        List<SWRLRule> builtInRules = ontology.axioms(AxiomType.SWRL_RULE)
                .filter(SwrlBuiltIns::usesBuiltIn).collect(Collectors.toList());
        if (builtInRules.isEmpty()) {
            return new PreparedOntology(ontology, 0, 0, 0, null);
        }
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology copy = manager.createOntology();
            manager.addAxioms(copy, ontology.axioms()
                    .filter(axiom -> !(axiom instanceof SWRLRule rule && usesBuiltIn(rule))));
            OWLDataFactory df = manager.getOWLDataFactory();
            List<SWRLRule> remaining = new ArrayList<>();
            int translated = 0;
            for (SWRLRule rule : builtInRules) {
                Optional<OWLAxiom> axiom = SwrlComparisonTranslator.translate(rule, df);
                if (axiom.isPresent()) {
                    manager.addAxiom(copy, axiom.get());
                    translated++;
                } else {
                    remaining.add(rule);
                }
            }
            int engineRules = 0;
            int leftOut = remaining.size();
            boolean engineDown = false;
            if (!remaining.isEmpty() && stage != null) {
                Optional<Set<OWLAxiom>> inferred = safeInfer(stage, ontology, remaining);
                if (inferred.isPresent()) {
                    manager.addAxioms(copy, inferred.get().stream());
                    engineRules = remaining.size();
                    leftOut = 0;
                } else {
                    engineDown = true;
                }
            }
            return new PreparedOntology(copy, translated, engineRules, leftOut, note(translated, engineRules, leftOut, engineDown));
        } catch (OWLOntologyCreationException e) {
            throw new IllegalStateException("Could not copy the ontology without its SWRL built-in rules", e);
        }
    }

    private static Optional<Set<OWLAxiom>> safeInfer(RuleStage stage, OWLOntology ontology, List<SWRLRule> rules) {
        try {
            Optional<Set<OWLAxiom>> result = stage.infer(ontology, rules);
            return result == null ? Optional.empty() : result;
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String note(int translated, int engineRules, int leftOut, boolean engineDown) {
        List<String> parts = new ArrayList<>();
        if (translated > 0) {
            parts.add(plural(translated) + " turned into OWL");
        }
        if (engineRules > 0) {
            parts.add(plural(engineRules) + " applied with the rule engine");
        }
        String text = "SWRL built-in functions are not supported by HermiT directly: " + String.join(" and ", parts);
        if (leftOut > 0) {
            String reason = engineDown ? "the rule engine could not be reached" : "no rule engine is available here";
            String left = plural(leftOut) + " left out of reasoning because " + reason;
            text = parts.isEmpty()
                    ? "HermiT does not support SWRL built-in functions, so " + left
                    : text + ", " + left;
        }
        return text + ".";
    }

    private static String plural(int count) {
        return count + " rule" + (count == 1 ? " was" : "s were");
    }

    public static String omissionNote(long count) {
        return "HermiT does not support SWRL built-in functions, so " + count + " rule" + (count == 1 ? "" : "s")
                + " using them " + (count == 1 ? "was" : "were") + " left out of reasoning.";
    }

    public static boolean usesBuiltIn(SWRLRule rule) {
        return Stream.concat(rule.body(), rule.head()).anyMatch(SWRLBuiltInAtom.class::isInstance);
    }
}
