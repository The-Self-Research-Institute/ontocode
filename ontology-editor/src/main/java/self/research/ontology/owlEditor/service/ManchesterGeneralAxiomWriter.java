package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLNaryClassAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

final class ManchesterGeneralAxiomWriter {

    private ManchesterGeneralAxiomWriter() {
    }

    static void save(OWLOntologyManager manager, OWLOntology ontology, OWLDocumentFormat format, OutputStream out)
            throws OWLOntologyStorageException, IOException {
        if (!(format instanceof ManchesterSyntaxDocumentFormat)) {
            manager.saveOntology(ontology, format, out);
            return;
        }
        Set<OWLNaryClassAxiom> general = new LinkedHashSet<>();
        for (OWLNaryClassAxiom ax : ontology.getAxioms(AxiomType.EQUIVALENT_CLASSES, Imports.EXCLUDED)) {
            if (ax.classExpressions().allMatch(ce -> ce.isAnonymous())) {
                general.add(ax);
            }
        }
        for (OWLNaryClassAxiom ax : ontology.getAxioms(AxiomType.DISJOINT_CLASSES, Imports.EXCLUDED)) {
            if (ax.classExpressions().allMatch(ce -> ce.isAnonymous())) {
                general.add(ax);
            }
        }
        if (general.isEmpty()) {
            manager.saveOntology(ontology, format, out);
            return;
        }
        manager.removeAxioms(ontology, general);
        try {
            manager.saveOntology(ontology, format, out);
        } finally {
            manager.addAxioms(ontology, general);
        }
        out.write(frames(general).getBytes(StandardCharsets.UTF_8));
    }

    private static String frames(Set<OWLNaryClassAxiom> general) {
        StringBuilder text = new StringBuilder();
        for (OWLNaryClassAxiom ax : general) {
            ManchesterOWLSyntaxOWLObjectRendererImpl renderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
            renderer.setShortFormProvider(entity -> "<" + entity.getIRI() + ">");
            String frame = ax instanceof OWLEquivalentClassesAxiom ? "EquivalentClasses: " : "DisjointClasses: ";
            text.append('\n').append(frame)
                    .append(ax.classExpressions()
                            .map(ce -> "(" + renderer.render(ce).replaceAll("\\s+", " ") + ")")
                            .collect(Collectors.joining(", ")))
                    .append('\n');
        }
        return text.toString();
    }
}
