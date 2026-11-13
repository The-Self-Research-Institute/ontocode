package self.research.ontology.migrator.core;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Options {

    public static final List<String> DEFAULT_BUCKETS = List.of("fs", "plugins", "desktop-installers");

    public String mongoUri;
    public String mongoDb = "ontocode";
    public String pgUrl;
    public String pgUser = "ontocode";
    public String pgPassword;
    public String blobRoot;
    public int batchSize = 1000;
    public int sampleSize = 200;
    public Set<String> only = new LinkedHashSet<>();
    public List<String> buckets = DEFAULT_BUCKETS;
    public boolean dryRun;
    public boolean verifyOnly;
    public boolean fresh;
    public boolean allowOrphans;
    public String reportDir = ".";

    public static Options parse(String[] args, Map<String, String> env) {
        Options o = new Options();
        o.mongoUri = env.get("ONTOCODE_MONGO_URI");
        o.pgPassword = env.get("ONTOCODE_DB_PASSWORD");
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--dry-run" -> o.dryRun = true;
                case "--verify-only" -> o.verifyOnly = true;
                case "--fresh" -> o.fresh = true;
                case "--allow-orphans" -> o.allowOrphans = true;
                case "--mongo-uri" -> o.mongoUri = value(args, ++i, arg);
                case "--mongo-db" -> o.mongoDb = value(args, ++i, arg);
                case "--pg-url" -> o.pgUrl = value(args, ++i, arg);
                case "--pg-user" -> o.pgUser = value(args, ++i, arg);
                case "--blob-root" -> o.blobRoot = value(args, ++i, arg);
                case "--report-dir" -> o.reportDir = value(args, ++i, arg);
                case "--batch-size" -> o.batchSize = positive(value(args, ++i, arg), arg);
                case "--sample-size" -> o.sampleSize = Integer.parseInt(value(args, ++i, arg));
                case "--only" -> o.only = new LinkedHashSet<>(Arrays.asList(value(args, ++i, arg).split("\\s*,\\s*")));
                case "--buckets" -> o.buckets = Arrays.asList(value(args, ++i, arg).split("\\s*,\\s*"));
                default -> throw new IllegalArgumentException("Unknown option " + arg);
            }
        }
        return o;
    }

    public void validate() {
        if (mongoUri == null || mongoUri.isBlank()) {
            throw new IllegalArgumentException("The MongoDB address is missing: set ONTOCODE_MONGO_URI or pass --mongo-uri");
        }
        if (!dryRun && (pgUrl == null || pgUrl.isBlank())) {
            throw new IllegalArgumentException("--pg-url is required");
        }
        if (dryRun && verifyOnly) {
            throw new IllegalArgumentException("--dry-run and --verify-only cannot be combined");
        }
        if (blobRoot == null && !dryRun) {
            throw new IllegalArgumentException("--blob-root is required");
        }
    }

    public boolean selected(String name) {
        return only.isEmpty() || only.contains(name);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static int positive(String text, String option) {
        int v = Integer.parseInt(text);
        if (v <= 0) {
            throw new IllegalArgumentException(option + " must be positive");
        }
        return v;
    }
}
