package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLRule;

import java.io.StringWriter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class RuleTestSupport {

    public static final String NS = "http://ex.org/o#";
    public static final String ONTO = "http://ex.org/o";
    public static final List<String> CLASSES = List.of("Person", "Agent", "Adult", "Student", "Teacher", "Londoner", "Parent");

    private RuleTestSupport() {
    }

    public static org.semanticweb.owlapi.model.IRI oi(String s) {
        return org.semanticweb.owlapi.model.IRI.create(s);
    }

    public static String seedTurtle() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory f = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(oi(ONTO));
        for (String c : CLASSES) {
            m.addAxiom(o, f.getOWLDeclarationAxiom(f.getOWLClass(oi(NS + c))));
        }
        m.addAxiom(o, f.getOWLSubClassOfAxiom(f.getOWLClass(oi(NS + "Student")), f.getOWLClass(oi(NS + "Person"))));
        StringDocumentTarget target = new StringDocumentTarget();
        m.saveOntology(o, new TurtleDocumentFormat(), target);
        return target.toString();
    }

    public static String functionalRule(String body, String head) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory f = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(oi("urn:test:holder"));
        var x = f.getSWRLVariable(oi(NS + "var/x"));
        m.addAxiom(o, f.getSWRLRule(
                Set.of(f.getSWRLClassAtom(f.getOWLClass(oi(NS + body)), x)),
                Set.of(f.getSWRLClassAtom(f.getOWLClass(oi(NS + head)), x))));
        StringDocumentTarget target = new StringDocumentTarget();
        m.saveOntology(o, new FunctionalSyntaxDocumentFormat(), target);
        return target.toString();
    }

    public static String functional(SWRLRule rule) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology(oi("urn:test:holder"));
        m.addAxiom(o, rule);
        StringDocumentTarget target = new StringDocumentTarget();
        m.saveOntology(o, new FunctionalSyntaxDocumentFormat(), target);
        return target.toString();
    }

    public static SWRLRule plainRule(String body, String head) throws Exception {
        return rulesOf(load(functionalRule(body, head))).iterator().next();
    }

    public static OWLOntology load(String text) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(text));
    }

    public static Set<SWRLRule> rulesOf(OWLOntology o) {
        return o.axioms(AxiomType.SWRL_RULE).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static Model parse(String text, RDFFormat format) throws Exception {
        return Rio.parse(new java.io.StringReader(text), "", format);
    }

    public static String toRdfXml(Model m) throws Exception {
        StringWriter w = new StringWriter();
        Rio.write(m, w, RDFFormat.RDFXML);
        return w.toString();
    }

    public static final class Store implements AutoCloseable {
        private final SailRepository repo;

        public Store() {
            repo = new SailRepository(new MemoryStore());
            repo.init();
        }

        public SailRepository repo() {
            return repo;
        }

        public IRI graph(String uri) {
            return SimpleValueFactory.getInstance().createIRI(uri);
        }

        public void put(String uri, Model m) {
            try (RepositoryConnection c = repo.getConnection()) {
                c.add(m, graph(uri));
            }
        }

        public void clear(String uri) {
            try (RepositoryConnection c = repo.getConnection()) {
                c.clear(graph(uri));
            }
        }

        public void copy(String from, String to) {
            try (RepositoryConnection c = repo.getConnection()) {
                GraphStatementOps.copy(c, from, to);
            }
        }

        public Model read(String uri) {
            try (RepositoryConnection c = repo.getConnection()) {
                Model flat = new LinkedHashModel();
                c.getStatements(null, null, null, false, graph(uri))
                        .forEach(s -> flat.add(s.getSubject(), s.getPredicate(), s.getObject()));
                return flat;
            }
        }

        public void update(String uri, String sparql) {
            try (RepositoryConnection c = repo.getConnection()) {
                var u = c.prepareUpdate(sparql);
                SimpleDataset ds = new SimpleDataset();
                IRI g = graph(uri);
                ds.addDefaultGraph(g);
                ds.addDefaultRemoveGraph(g);
                ds.setDefaultInsertGraph(g);
                u.setDataset(ds);
                u.execute();
            }
        }

        public OWLOntology ontology(String uri) {
            try {
                return load(toRdfXml(read(uri)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            repo.shutDown();
        }
    }
}
