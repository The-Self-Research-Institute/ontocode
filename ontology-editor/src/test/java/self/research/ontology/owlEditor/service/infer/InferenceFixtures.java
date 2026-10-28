package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.owlEditor.service.SparqlGraphUris;
import self.research.ontology.owlEditor.service.SparqlQueryScope;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class InferenceFixtures {

    static final String PROJECT = "projInfer";
    static final String E = "http://e/";
    static final String PREFIXES = "@prefix : <http://e/> . @prefix owl: <http://www.w3.org/2002/07/owl#> . "
            + "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> . @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> . "
            + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> . ";

    private InferenceFixtures() {
    }

    static SparqlQueryScope publicScope() {
        return SparqlQueryScope.publicScope(PROJECT, SparqlGraphUris.mainProjectGraph(PROJECT), List.of());
    }

    static SparqlQueryScope draftScope(String userId) {
        return SparqlQueryScope.draftScope(PROJECT, userId);
    }

    static List<Statement> turtle(String body, String graph) {
        try {
            IRI g = SimpleValueFactory.getInstance().createIRI(graph);
            return new ArrayList<>(Rio.parse(new StringReader(PREFIXES + body), "", RDFFormat.TURTLE, g));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Statement> rules(String graph, List<RuleSpec> specs) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = manager.createOntology();
            OWLDataFactory df = manager.getOWLDataFactory();
            for (RuleSpec spec : specs) {
                manager.addAxiom(ontology, spec.build(df));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            manager.saveOntology(ontology, new NTriplesDocumentFormat(), out);
            IRI g = SimpleValueFactory.getInstance().createIRI(graph);
            List<Statement> parsed = new ArrayList<>(Rio.parse(new ByteArrayInputStream(out.toByteArray()), "", RDFFormat.NTRIPLES, g));
            parsed.removeIf(st -> st.getObject().stringValue().equals("http://www.w3.org/2002/07/owl#Ontology"));
            return parsed;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record RuleSpec(Function<OWLDataFactory, Set<SWRLAtom>> body, Function<OWLDataFactory, Set<SWRLAtom>> head,
                    String label, boolean enabled) {
        SWRLRule build(OWLDataFactory df) {
            Set<org.semanticweb.owlapi.model.OWLAnnotation> annotations = new java.util.HashSet<>();
            if (label != null) {
                annotations.add(df.getRDFSLabel(label));
            }
            if (!enabled) {
                annotations.add(df.getOWLAnnotation(df.getOWLAnnotationProperty(
                        org.semanticweb.owlapi.model.IRI.create("http://ontocode.org/ns#enabled")), df.getOWLLiteral("false")));
            }
            return df.getSWRLRule(body.apply(df), head.apply(df), annotations);
        }
    }

    static final class MutableSource implements self.research.ontology.owlEditor.service.infer.InferenceSource {
        final Map<String, List<Statement>> byGraph = new HashMap<>();
        final Map<String, Long> revisions = new HashMap<>();
        Runnable beforeRead = () -> { };

        void set(SparqlQueryScope scope, List<Statement> statements) {
            byGraph.put(scope.graphs().get(0), new ArrayList<>(statements));
            revisions.merge(scope.graphs().get(0), 1L, Long::sum);
        }

        void bump(SparqlQueryScope scope) {
            revisions.merge(scope.graphs().get(0), 1L, Long::sum);
        }

        @Override
        public String revision(SparqlQueryScope scope, String userId) {
            return "r" + revisions.getOrDefault(scope.graphs().get(0), 0L);
        }

        @Override
        public List<Statement> read(SparqlQueryScope scope, int maxStatements) {
            beforeRead.run();
            List<Statement> all = new ArrayList<>();
            for (String g : scope.graphs()) {
                all.addAll(byGraph.getOrDefault(g, List.of()));
            }
            if (all.size() > maxStatements) {
                throw new InferenceException(InferenceException.TOO_LARGE, "too large");
            }
            return all;
        }
    }
}
