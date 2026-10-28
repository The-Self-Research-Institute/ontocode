package self.research.ontology.common.admission;

import java.time.Duration;
import java.util.List;

public enum WorkKind {
    EXECUTE_RULES("ruleExecute", 0.40, 1, 1, 5, 20, "owl-editor", "swrl-service"),
    DL_QUERY("loadAndReason", 0.30, 1, 1, 5, 20, "owl-editor", "reasoner-worker"),
    REASONING("loadAndReason", 0.40, 1, 1, 5, 20, "owl-editor", "reasoner-worker"),
    INFERENCE_BUILD("loadAndReason", 0.30, 1, 1, 5, 20, "owl-editor", "reasoner-worker"),
    SPARQL_QUERY("sparqlRows", 0.25, 2, 2, 2, 20, "owl-editor"),
    IMPORT_PARSE("importParse", 0.35, 1, 1, 5, 5, "owl-editor"),
    UPLOAD("importParse", 0.15, 2, 1, 5, 10, "owl-editor", "gateway"),
    SPREADSHEET_IMPORT("importParse", 0.15, 1, 1, 5, 10, "plugin-service"),
    EXPORT("loadAndReason", 0.15, 1, 1, 5, 10, "owl-editor"),
    ASSISTANT_TOOL("fixed", 0.15, 2, 1, 5, 20, "owl-editor"),
    SWRL_RULE_RUN("ruleExecute", 0.15, 1, 1, 5, 10, "swrl-service");

    private final String estimateModel;
    private final double defaultMaxShare;
    private final int defaultMaxActivePerOwner;
    private final int defaultMaxWaitingPerOwner;
    private final Duration defaultMaxWait;
    private final int defaultMaxWaiters;
    private final List<String> services;

    WorkKind(String estimateModel, double defaultMaxShare, int defaultMaxActivePerOwner,
            int defaultMaxWaitingPerOwner, int defaultMaxWaitSeconds, int defaultMaxWaiters, String... services) {
        this.estimateModel = estimateModel;
        this.defaultMaxShare = defaultMaxShare;
        this.defaultMaxActivePerOwner = defaultMaxActivePerOwner;
        this.defaultMaxWaitingPerOwner = defaultMaxWaitingPerOwner;
        this.defaultMaxWait = Duration.ofSeconds(defaultMaxWaitSeconds);
        this.defaultMaxWaiters = defaultMaxWaiters;
        this.services = List.of(services);
    }

    public String estimateModel() {
        return estimateModel;
    }

    public double defaultMaxShare() {
        return defaultMaxShare;
    }

    public int defaultMaxActivePerOwner() {
        return defaultMaxActivePerOwner;
    }

    public int defaultMaxWaitingPerOwner() {
        return defaultMaxWaitingPerOwner;
    }

    public Duration defaultMaxWait() {
        return defaultMaxWait;
    }

    public int defaultMaxWaiters() {
        return defaultMaxWaiters;
    }

    public List<String> services() {
        return services;
    }

    public String configKey() {
        return name().toLowerCase().replace('_', '-');
    }

    public KindSettings defaultSettings() {
        return new KindSettings(name(), defaultMaxShare, defaultMaxActivePerOwner, defaultMaxWaitingPerOwner,
                defaultMaxWait, defaultMaxWaiters);
    }

    public static WorkKind find(String name) {
        if (name == null) {
            return null;
        }
        for (WorkKind kind : values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
