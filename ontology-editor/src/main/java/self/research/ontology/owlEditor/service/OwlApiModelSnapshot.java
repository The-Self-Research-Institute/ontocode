package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

@Slf4j
public final class OwlApiModelSnapshot {

    private OwlApiModelSnapshot() {
    }

    public static Optional<Model> of(ProjectOntologyCache cache, String projectId) {
        if (cache == null) {
            return Optional.empty();
        }
        return cache.get(projectId).flatMap(cached -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                cached.manager().saveOntology(cached.ontology(), new RDFXMLDocumentFormat(), out);
                return Optional.of(Rio.parse(new ByteArrayInputStream(out.toByteArray()), "", RDFFormat.RDFXML));
            } catch (Exception e) {
                log.warn("[UNDO] Could not read the in-memory ontology of project {}: {}", projectId, e.getMessage());
                return Optional.empty();
            }
        });
    }
}
