package self.research.ontology.owlEditor.upload;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class UploadSettings {

    public record Rule(String name, List<String> patterns, long limitBytes) {
    }

    private static final String P = "ontocode.capacity.upload.";
    private static final String LEGACY = "ontocode.upload.";
    private static final long MIB = 1024L * 1024;

    private static final Map<String, List<String>> PATTERNS = new LinkedHashMap<>();
    private static final Map<String, Long> DEFAULTS = new LinkedHashMap<>();

    static {
        PATTERNS.put("chunk", List.of("/api/ontology/upload-chunk/**"));
        PATTERNS.put("project", List.of("/api/ontology/upload/**"));
        PATTERNS.put("spreadsheet", List.of("/api/ontology/spreadsheet-import/**"));
        PATTERNS.put("rule-import", List.of("/api/ontology/*/rules/import"));
        PATTERNS.put("merge", List.of("/api/projects/*/merge/**"));
        PATTERNS.put("issue", List.of("/api/v1/issues/report"));
        DEFAULTS.put("chunk", 32 * MIB);
        DEFAULTS.put("project", 1024 * MIB);
        DEFAULTS.put("spreadsheet", 25 * MIB);
        DEFAULTS.put("rule-import", 8 * MIB);
        DEFAULTS.put("merge", 1024 * MIB);
        DEFAULTS.put("issue", 50 * MIB);
    }

    private final Environment env;

    public UploadSettings(Environment env) {
        this.env = env;
    }

    public List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : PATTERNS.entrySet()) {
            rules.add(new Rule(e.getKey(), e.getValue(),
                    getLong("max-bytes." + e.getKey(), DEFAULTS.get(e.getKey()))));
        }
        return rules;
    }

    public int maxConcurrent() {
        return (int) getLong("max-concurrent", 3);
    }

    public long maxWaitMs() {
        return getLong("max-wait-ms", 2000);
    }

    public long staleAgeMs() {
        return getLong("sweep.stale-age-ms", 2 * 60 * 60 * 1000L);
    }

    public ArchiveLimits archiveLimits() {
        return new ArchiveLimits(
                getLong("archive.gzip-max-bytes", 4096 * MIB),
                getLong("archive.gzip-max-ratio", 200),
                (int) getLong("archive.zip-max-entries", 5000),
                getLong("archive.zip-max-total-bytes", 4096 * MIB),
                getLong("archive.zip-max-entry-bytes", 2048 * MIB));
    }

    private long getLong(String key, long def) {
        String v = env == null ? null : env.getProperty(P + key);
        if (v == null && env != null) {
            v = env.getProperty(LEGACY + key);
        }
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
