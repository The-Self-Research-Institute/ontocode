package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.OWLDocumentFormat;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;

import java.util.LinkedHashMap;
import java.util.Map;

final class LivePrefixes {

    private LivePrefixes() {
    }

    static Map<String, String> of(ProjectOntologyCache cache, DesktopOwlApiMutationService desktopOwlApi, String projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        if (cache == null || desktopOwlApi == null || !desktopOwlApi.isAuthoritative(projectId)) {
            return out;
        }
        cache.get(projectId).ifPresent(entry -> {
            OWLDocumentFormat format = entry.manager().getOntologyFormat(entry.ontology());
            if (format != null && format.isPrefixOWLDocumentFormat()) {
                format.asPrefixOWLDocumentFormat().getPrefixName2PrefixMap().forEach((name, namespace) -> {
                    String prefix = name.endsWith(":") ? name.substring(0, name.length() - 1) : name;
                    if (!"xml".equals(prefix) && namespace != null) {
                        out.put(prefix, namespace);
                    }
                });
            }
        });
        return out;
    }
}
