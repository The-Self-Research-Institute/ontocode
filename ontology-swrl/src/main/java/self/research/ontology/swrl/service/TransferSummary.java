package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import self.research.ontology.swrl.model.SkippedRule;

import java.util.ArrayList;
import java.util.List;

public record TransferSummary(int rulesExported, int classDeclarations, int individualDeclarations,
                              int objectPropertyDeclarations, int dataPropertyDeclarations, long totalAxioms,
                              List<SkippedRule> skippedRules, long elapsedMs) {

    public static final String QUERY_REASON = "This is a SQWRL query. It is not run as a rule.";
    public static final String DISABLED_REASON = "This rule is disabled.";

    public static TransferSummary of(OWLOntology ontology) {
        long start = System.nanoTime();
        int exported = 0;
        List<SkippedRule> skipped = new ArrayList<>();
        for (OntologyRules.Entry entry : OntologyRules.collect(ontology, true)) {
            if (entry.query()) {
                skipped.add(new SkippedRule(entry.name(), QUERY_REASON));
            } else if (!entry.enabled()) {
                skipped.add(new SkippedRule(entry.name(), DISABLED_REASON));
            } else {
                exported++;
            }
        }
        int classes = (int) ontology.getClassesInSignature(Imports.INCLUDED).stream().filter(c -> !c.isBuiltIn()).count();
        int individuals = ontology.getIndividualsInSignature(Imports.INCLUDED).size();
        int objectProperties = (int) ontology.getObjectPropertiesInSignature(Imports.INCLUDED).stream().filter(p -> !p.isBuiltIn()).count();
        int dataProperties = (int) ontology.getDataPropertiesInSignature(Imports.INCLUDED).stream().filter(p -> !p.isBuiltIn()).count();
        long total = ontology.getAxiomCount(Imports.INCLUDED) - ontology.getAxiomCount(AxiomType.SWRL_RULE, Imports.INCLUDED);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return new TransferSummary(exported, classes, individuals, objectProperties, dataProperties, total, skipped, elapsed);
    }
}
