package self.research.ontology.owlEditor.config;

import java.util.List;
import java.util.Optional;

public final class StompDestinations {

    private static final List<String> PROJECT_PREFIXES = List.of(
            "/topic/ontology/", "/topic/locks/", "/topic/presence/", "/topic/graph/", "/topic/import/",
            "/topic/queue/", "/app/collab/", "/app/graph/");
    private static final String SHARES_PREFIX = "/topic/shares/";
    private static final String STATS_SEGMENT = "stats";

    private StompDestinations() {
    }

    public static Optional<String> projectIdOf(String destination) {
        if (destination == null) {
            return Optional.empty();
        }
        for (String prefix : PROJECT_PREFIXES) {
            if (destination.startsWith(prefix)) {
                String rest = destination.substring(prefix.length());
                int slash = rest.indexOf('/');
                String id = slash < 0 ? rest : rest.substring(0, slash);
                return id.isBlank() || STATS_SEGMENT.equals(id) ? Optional.empty() : Optional.of(id);
            }
        }
        return Optional.empty();
    }

    public static Optional<String> sharesEmailOf(String destination) {
        return destination != null && destination.startsWith(SHARES_PREFIX)
                ? Optional.of(destination.substring(SHARES_PREFIX.length()))
                : Optional.empty();
    }

    public static boolean isPattern(String destination) {
        return destination != null && (destination.indexOf('*') >= 0 || destination.indexOf('?') >= 0
                || destination.indexOf('{') >= 0 || destination.indexOf('}') >= 0);
    }

    public static boolean isBrokerDestination(String destination) {
        return destination != null && (destination.startsWith("/topic/") || destination.startsWith("/queue/"));
    }
}
