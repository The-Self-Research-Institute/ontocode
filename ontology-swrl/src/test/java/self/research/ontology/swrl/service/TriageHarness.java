package self.research.ontology.swrl.service;

import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.model.SwrlRule;
import self.research.ontology.swrl.repository.SwrlRuleRepository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TriageHarness {

    static final String PROJECT = "p1";

    final OntologyClientService client = Mockito.mock(OntologyClientService.class);
    final SwrlRuleRepository repository = Mockito.mock(SwrlRuleRepository.class);
    final SwrlEngineService service = new SwrlEngineService();
    private final List<SWRLRule> ruleAxioms = new ArrayList<>();
    private final boolean embedded;

    TriageHarness(SwrlRule... rules) throws Exception {
        this(false, rules);
    }

    private TriageHarness(boolean embedded, SwrlRule... rules) throws Exception {
        this.embedded = embedded;
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "ruleRepository", repository);
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        if (!embedded) {
            SwrlEngineService parser = new SwrlEngineService();
            OntologyClientService parserClient = Mockito.mock(OntologyClientService.class);
            Mockito.when(parserClient.fetchOntology(Mockito.anyString())).thenAnswer(invocation -> bare());
            ReflectionTestUtils.setField(parser, "ontologyClient", parserClient);
            ReflectionTestUtils.setField(parser, "maxInferredAxioms", 1000);
            ReflectionTestUtils.setField(parser, "executionTimeoutSeconds", 60);
            for (SwrlRule rule : rules) {
                SwrlEngineService.RuleParse parsed = parser.parseRule(PROJECT, rule.getRuleText(), null);
                if (!parsed.valid()) {
                    continue;
                }
                ruleAxioms.add(annotated(parsed.axiom(), rule));
            }
        }
        Mockito.when(client.fetchOntology(Mockito.anyString())).thenAnswer(invocation -> fresh());
        Mockito.when(client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> new OntologyClientService.FetchedOntology(fresh(), 1L, null));
    }

    void addAxiom(SWRLRule axiom) {
        ruleAxioms.add(axiom);
    }

    static TriageHarness withEmbeddedRules() throws Exception {
        return new TriageHarness(true);
    }

    OWLOntology fresh() throws Exception {
        if (embedded) {
            return triage();
        }
        OWLOntology ontology = bare();
        ontology.getOWLOntologyManager().addAxioms(ontology, new HashSet<>(ruleAxioms));
        PlainLiteralNormalizer.apply(ontology);
        return ontology;
    }

    private static SWRLRule annotated(String functional, SwrlRule rule) throws Exception {
        OWLOntology parsed = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(functional));
        SWRLRule axiom = parsed.getAxioms(AxiomType.SWRL_RULE).iterator().next();
        OWLDataFactory factory = OWLManager.getOWLDataFactory();
        Set<OWLAnnotation> annotations = new HashSet<>();
        annotations.add(factory.getOWLAnnotation(factory.getRDFSLabel(), factory.getOWLLiteral(rule.getRuleName())));
        if (rule.getComment() != null) {
            annotations.add(factory.getOWLAnnotation(factory.getRDFSComment(), factory.getOWLLiteral(rule.getComment())));
        }
        if (rule.getCategory() != null) {
            annotations.add(factory.getOWLAnnotation(factory.getOWLAnnotationProperty(OntologyRules.CATEGORY), factory.getOWLLiteral(rule.getCategory())));
        }
        if (rule.getId() != null) {
            annotations.add(factory.getOWLAnnotation(factory.getOWLAnnotationProperty(OntologyRules.RULE_ID), factory.getOWLLiteral(rule.getId())));
        }
        if (!rule.isEnabled()) {
            annotations.add(factory.getOWLAnnotation(factory.getOWLAnnotationProperty(OntologyRules.ENABLED), factory.getOWLLiteral(false)));
        }
        return (SWRLRule) axiom.getAxiomWithoutAnnotations().getAnnotatedAxiom(annotations);
    }

    static String triageText() throws Exception {
        try (var in = TriageHarness.class.getResourceAsStream("/triage_demo.owl")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static OWLOntology triage() throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(triageText()));
    }

    static OWLOntology bare() throws Exception {
        OWLOntology ontology = triage();
        ontology.getOWLOntologyManager().removeAxioms(ontology, new HashSet<>(ontology.getAxioms(AxiomType.SWRL_RULE)));
        return ontology;
    }

    static SwrlRule rule(String id, String name, String text) {
        SwrlRule rule = new SwrlRule(PROJECT, name, text);
        rule.setId(id);
        rule.setEnabled(true);
        return rule;
    }
}
