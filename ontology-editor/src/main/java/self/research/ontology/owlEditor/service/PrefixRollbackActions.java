package self.research.ontology.owlEditor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PrefixRollbackActions implements RollbackActionRunner {

    private static final ThreadLocal<Boolean> SUPPRESS_HISTORY = new ThreadLocal<>();

    private final OntologyMetadataService metadataService;

    @Autowired
    public PrefixRollbackActions(@Lazy OntologyMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    public static boolean historySuppressed() {
        return Boolean.TRUE.equals(SUPPRESS_HISTORY.get());
    }

    @Override
    public void run(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        SUPPRESS_HISTORY.set(true);
        try {
            switch (action.type()) {
                case RollbackAction.SET_PREFIX -> metadataService.updatePrefix(projectId, action.prefix(),
                        action.namespace(), action.oldPrefix(), draft, draftOwner, draftOwner, draftOwner);
                case RollbackAction.DELETE_PREFIX -> metadataService.deletePrefix(projectId, action.prefix(), draft,
                        draftOwner, draftOwner, draftOwner);
                case RollbackAction.REFRESH_ONTOLOGY -> {
                    if (!draft) {
                        metadataService.refreshOntologyMetadata(projectId);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown rollback action " + action.type());
            }
        } finally {
            SUPPRESS_HISTORY.remove();
        }
    }

    @Override
    public boolean holds(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        if (RollbackAction.REFRESH_ONTOLOGY.equals(action.type()) || draft && action.expectAbsent()) {
            return true;
        }
        String current = draft ? currentDraftNamespace(projectId, action, draftOwner) : currentNamespace(projectId, action);
        if (action.expectAbsent()) {
            return current == null || current.equals(OntologyMetadataService.STANDARD_PREFIXES.get(action.prefix()));
        }
        if (RollbackAction.DELETE_PREFIX.equals(action.type()) && current == null) {
            return true;
        }
        return action.expectedNamespace() == null || action.expectedNamespace().equals(current);
    }

    private String currentDraftNamespace(String projectId, RollbackAction action, String draftOwner) {
        String previous = SparqlQueryContext.getUserId();
        SparqlQueryContext.setUserId(draftOwner);
        try {
            return currentNamespace(projectId, action);
        } finally {
            SparqlQueryContext.setUserId(previous);
        }
    }

    private String currentNamespace(String projectId, RollbackAction action) {
        List<Map<String, String>> prefixes = metadataService.getPrefixes(projectId);
        String lookup = SET_LOOKUP.equals(action.type()) && action.oldPrefix() != null && action.expectedNamespace() != null
                ? action.oldPrefix() : action.prefix();
        for (Map<String, String> entry : prefixes) {
            if (lookup.equals(entry.get("prefix"))) {
                return entry.get("namespace");
            }
        }
        return null;
    }

    private static final String SET_LOOKUP = RollbackAction.SET_PREFIX;
}
