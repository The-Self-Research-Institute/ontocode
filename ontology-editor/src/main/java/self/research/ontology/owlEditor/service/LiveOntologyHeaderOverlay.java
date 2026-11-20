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
        if (!(metadata.get("tripleCount") instanceof Number) || Boolean.TRUE.equals(metadata.get("tripleCountEstimated"))) {
            Integer indexed = indexedTriples(metadata);
            metadata.put("tripleCount", indexed != null ? indexed : ontology.getAxiomCount() * 2);
            if (indexed != null) {
                metadata.remove("tripleCountEstimated");
            } else {
                metadata.put("tripleCountEstimated", true);
            }
        }
    }

    private static Integer indexedTriples(Map<String, Object> metadata) {
        if (metadata.get("counts") instanceof Map<?, ?> counts && counts.get("triples") instanceof Number triples
                && triples.intValue() > 0) {
            return triples.intValue();
        }
        return null;
    }
}
