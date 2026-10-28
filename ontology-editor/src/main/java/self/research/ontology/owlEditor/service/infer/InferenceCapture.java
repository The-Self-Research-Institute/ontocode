package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.common.rules.DisabledRules;
import self.research.ontology.common.rules.OntologyRulesFingerprint;
import self.research.ontology.owlEditor.service.SparqlQueryScope;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

public final class InferenceCapture {

    public record Captured(String revision, List<Statement> statements, OWLOntology ontology, String rulesFingerprint,
                           int enabledRules) {
    }

    private final InferenceSource source;
    private final int maxAsserted;

    public InferenceCapture(InferenceSource source, int maxAsserted) {
        this.source = source;
        this.maxAsserted = maxAsserted;
    }

    public Captured capture(SparqlQueryScope scope, String userId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String before = source.revision(scope, userId);
            List<Statement> statements = source.read(scope, maxAsserted);
            String after = source.revision(scope, userId);
            if (before.equals(after)) {
                return build(before, statements);
            }
        }
        throw new InferenceException(InferenceException.CHANGED,
                "The ontology changed while it was being read. Try again in a moment.");
    }

    private static Captured build(String revision, List<Statement> statements) {
        OWLOntology ontology = load(statements);
        String fingerprint = OntologyRulesFingerprint.of(ontology);
        DisabledRules.strip(ontology);
        int enabled = (int) ontology.axioms(AxiomType.SWRL_RULE).count();
        return new Captured(revision, statements, ontology, fingerprint, enabled);
    }

    static OWLOntology load(List<Statement> statements) {
        try {
            Bytes bytes = new Bytes();
            Rio.write(statements, bytes, RDFFormat.NTRIPLES);
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            StreamDocumentSource document = new StreamDocumentSource(bytes.reader(),
                    IRI.create("urn:ontocode:inference:capture"), new NTriplesDocumentFormat(), "application/n-triples");
            return manager.loadOntologyFromOntologyDocument(document);
        } catch (Exception e) {
            throw new IllegalStateException("The captured data could not be read as an ontology: " + e.getMessage(), e);
        }
    }

    private static final class Bytes extends ByteArrayOutputStream {
        ByteArrayInputStream reader() {
            return new ByteArrayInputStream(buf, 0, count);
        }
    }
}
