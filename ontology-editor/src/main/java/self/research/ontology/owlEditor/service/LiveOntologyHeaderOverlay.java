package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.OWLOntology;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyMetadataQueryService;

import java.util.Map;

final class LiveOntologyHeaderOverlay {

    private LiveOntologyHeaderOverlay() {
    }

    static void apply(Map<String, Object> metadata, String projectId,
                      ProjectOntologyCache cache, DesktopOwlApiMutationService desktopOwlApi) {
        if (cache == null || desktopOwlApi == null || !desktopOwlApi.isAuthoritative(projectId)) {
            return;
        }
        cache.get(projectId).ifPresent(entry -> overlay(metadata, entry.ontology()));
    }

    static void overlay(Map<String, Object> metadata, OWLOntology ontology) {
        var id = ontology.getOntologyID();
        if (id.getOntologyIRI().isPresent()) {
            metadata.put("ontologyIRI", id.getOntologyIRI().get().toString());
            if (id.getVersionIRI().isPresent()) {
                metadata.put("versionIRI", id.getVersionIRI().get().toString());
            } else {
                metadata.remove("versionIRI");
            }
        }
        metadata.put("annotations", OwlApiOntologyMetadataQueryService.buildAnnotations(ontology));
    }
}
