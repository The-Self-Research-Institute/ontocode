package self.research.ontology.common.admission;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

public final class CapacityConfig {

    public static final String PREFIX = "ontocode.capacity.";

    public enum Origin { DEFAULT, PROPERTY, ENVIRONMENT, ALIAS }

    public record ValueSource(Origin origin, String name) {
        @Override
        public String toString() {
            return origin == Origin.DEFAULT ? "default" : origin.name().toLowerCase() + ":" + name;
        }
    }

    public static final Map<String, String> DEFAULT_ALIASES = Map.of(
            "ontocode.admission.headroomFraction", "headroom-fraction",
            "ontocode.admission.heapAfterGcRefuseRatio", "heap-after-gc-refuse-ratio",
            "ontocode.admission.containerRefuseRatio", "container-refuse-ratio",
            "ontocode.admission.maxWaitMillis", "max-wait-millis",
            "ontocode.admission.maxWaiters", "max-waiters",
            "ontocode.admission.maxActivePerOwner", "max-active-per-owner",
            "ontocode.admission.maxWaitingPerOwner", "max-waiting-per-owner",
            "ontocode.admission.watchdogMaxAgeMinutes", "watchdog-max-age-minutes");

    private final AdmissionProperties admission;
    private final Map<WorkKind, KindSettings> kinds;
    private final Map<String, String> values;
    private final Map<String, ValueSource> sources;

    private CapacityConfig(AdmissionProperties admission, Map<WorkKind, KindSettings> kinds,
            Map<String, String> values, Map<String, ValueSource> sources) {
        this.admission = admission;
        this.kinds = kinds;
        this.values = values;
        this.sources = sources;
    }

    public static CapacityConfig defaults() {
        return load(k -> null, k -> null, Map.of());
    }

    public static CapacityConfig of(AdmissionProperties admission) {
        Map<WorkKind, KindSettings> kinds = new EnumMap<>(WorkKind.class);
        Map<String, String> values = new TreeMap<>();
        Map<String, ValueSource> sources = new TreeMap<>();
        for (WorkKind kind : WorkKind.values()) {
            kinds.put(kind, kind.defaultSettings());
        }
        record(values, sources, "headroom-fraction", Double.toString(admission.headroomFraction()), null);
        record(values, sources, "heap-after-gc-refuse-ratio", Double.toString(admission.heapAfterGcRefuseRatio()), null);
        record(values, sources, "container-refuse-ratio", Double.toString(admission.containerRefuseRatio()), null);
        record(values, sources, "max-wait-millis", Long.toString(admission.maxWait().toMillis()), null);
        record(values, sources, "max-waiters", Integer.toString(admission.maxWaiters()), null);
        record(values, sources, "max-active-per-owner", Integer.toString(admission.maxActivePerOwner()), null);
        record(values, sources, "max-waiting-per-owner", Integer.toString(admission.maxWaitingPerOwner()), null);
        record(values, sources, "watchdog-max-age-minutes", Long.toString(admission.watchdogMaxAge().toMinutes()), null);
        for (WorkKind kind : WorkKind.values()) {
            recordKind(values, sources, kind, kinds.get(kind), null);
        }
        return new CapacityConfig(admission, kinds, values, sources);
    }

    public static CapacityConfig load() {
        return load(System::getProperty, System::getenv, Map.of());
    }

    public static CapacityConfig load(Function<String, String> properties, Function<String, String> environment,
            Map<String, String> extraAliases) {
        Map<String, String> aliasToKey = new TreeMap<>(DEFAULT_ALIASES);
        aliasToKey.putAll(extraAliases);
        Map<String, java.util.List<String>> keyToAliases = new TreeMap<>();
        for (Map.Entry<String, String> e : aliasToKey.entrySet()) {
            String key = e.getValue().startsWith(PREFIX) ? e.getValue().substring(PREFIX.length()) : e.getValue();
            keyToAliases.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(e.getKey());
        }
        Resolver r = new Resolver(properties, environment, keyToAliases);
        AdmissionProperties d = AdmissionProperties.defaults();
        double headroom = r.decimal("headroom-fraction", d.headroomFraction(), v -> v >= 0 && v < 1);
        double heap = r.decimal("heap-after-gc-refuse-ratio", d.heapAfterGcRefuseRatio(), v -> v > 0 && v <= 1);
        double container = r.decimal("container-refuse-ratio", d.containerRefuseRatio(), v -> v > 0 && v <= 1);
        long waitMillis = r.whole("max-wait-millis", d.maxWait().toMillis(), v -> v >= 0);
        long waiters = r.whole("max-waiters", d.maxWaiters(), v -> v >= 0 && v <= Integer.MAX_VALUE);
        long active = r.whole("max-active-per-owner", d.maxActivePerOwner(), v -> v >= 1 && v <= Integer.MAX_VALUE);
        long waiting = r.whole("max-waiting-per-owner", d.maxWaitingPerOwner(), v -> v >= 0 && v <= Integer.MAX_VALUE);
        long watchdog = r.whole("watchdog-max-age-minutes", d.watchdogMaxAge().toMinutes(), v -> v >= 1);
        AdmissionProperties admission = new AdmissionProperties(headroom, heap, container,
                Duration.ofMillis(waitMillis), (int) waiters, (int) active, (int) waiting, Duration.ofMinutes(watchdog));
        Map<WorkKind, KindSettings> kinds = new EnumMap<>(WorkKind.class);
        for (WorkKind kind : WorkKind.values()) {
            String base = "kind." + kind.configKey() + ".";
            double share = r.decimal(base + "max-share", kind.defaultMaxShare(), v -> v > 0 && v <= 1);
            long kActive = r.whole(base + "max-active-per-owner", kind.defaultMaxActivePerOwner(),
                    v -> v >= 1 && v <= Integer.MAX_VALUE);
            long kWaiting = r.whole(base + "max-waiting-per-owner", kind.defaultMaxWaitingPerOwner(),
                    v -> v >= 0 && v <= Integer.MAX_VALUE);
            long kWait = r.whole(base + "max-wait-millis", kind.defaultMaxWait().toMillis(), v -> v >= 0);
            long kWaiters = r.whole(base + "max-waiters", kind.defaultMaxWaiters(),
                    v -> v >= 0 && v <= Integer.MAX_VALUE);
            kinds.put(kind, new KindSettings(kind.name(), share, (int) kActive, (int) kWaiting,
                    Duration.ofMillis(kWait), (int) kWaiters));
        }
        return new CapacityConfig(admission, kinds, r.values, r.sources);
    }

    public AdmissionProperties admission() {
        return admission;
    }

    public KindSettings kind(WorkKind kind) {
        return kinds.get(kind);
    }

    public KindSettings settingsFor(String kindName) {
        WorkKind kind = WorkKind.find(kindName);
        if (kind != null) {
            return kinds.get(kind);
        }
        return KindSettings.fromProperties(kindName, admission);
    }

    public Map<String, String> effectiveValues() {
        return Map.copyOf(values);
    }

    public Map<String, ValueSource> sources() {
        return Map.copyOf(sources);
    }

    private static void record(Map<String, String> values, Map<String, ValueSource> sources, String key,
            String value, ValueSource source) {
        values.put(key, value);
        sources.put(key, source == null ? new ValueSource(Origin.DEFAULT, key) : source);
    }

    private static void recordKind(Map<String, String> values, Map<String, ValueSource> sources, WorkKind kind,
            KindSettings s, ValueSource source) {
        String base = "kind." + kind.configKey() + ".";
        record(values, sources, base + "max-share", Double.toString(s.maxShare()), source);
        record(values, sources, base + "max-active-per-owner", Integer.toString(s.maxActivePerOwner()), source);
        record(values, sources, base + "max-waiting-per-owner", Integer.toString(s.maxWaitingPerOwner()), source);
        record(values, sources, base + "max-wait-millis", Long.toString(s.maxWait().toMillis()), source);
        record(values, sources, base + "max-waiters", Integer.toString(s.maxWaiters()), source);
    }

    public static String environmentName(String key) {
        return "ONTOCODE_CAPACITY_" + key.toUpperCase().replace('-', '_').replace('.', '_');
    }

    private static final class Resolver {
        private final Function<String, String> properties;
        private final Function<String, String> environment;
        private final Map<String, java.util.List<String>> aliases;
        final Map<String, String> values = new TreeMap<>();
        final Map<String, ValueSource> sources = new TreeMap<>();

        Resolver(Function<String, String> properties, Function<String, String> environment,
                Map<String, java.util.List<String>> aliases) {
            this.properties = properties;
            this.environment = environment;
            this.aliases = aliases;
        }

        private String[] find(String key, java.util.function.Predicate<String> usable) {
            String name = PREFIX + key;
            String v = properties.apply(name);
            if (v != null && !v.isBlank() && usable.test(v.trim())) {
                return new String[] {v.trim(), Origin.PROPERTY.name(), name};
            }
            String env = environmentName(key);
            v = environment.apply(env);
            if (v != null && !v.isBlank() && usable.test(v.trim())) {
                return new String[] {v.trim(), Origin.ENVIRONMENT.name(), env};
            }
            for (String alias : aliases.getOrDefault(key, java.util.List.of())) {
                v = properties.apply(alias);
                if (v != null && !v.isBlank() && usable.test(v.trim())) {
                    return new String[] {v.trim(), Origin.ALIAS.name(), alias};
                }
                v = environment.apply(alias);
                if (v != null && !v.isBlank() && usable.test(v.trim())) {
                    return new String[] {v.trim(), Origin.ALIAS.name(), alias};
                }
            }
            return null;
        }

        double decimal(String key, double fallback, java.util.function.DoublePredicate valid) {
            String[] hit = find(key, t -> {
                try {
                    return valid.test(Double.parseDouble(t));
                } catch (NumberFormatException e) {
                    return false;
                }
            });
            if (hit == null) {
                record(values, sources, key, Double.toString(fallback), null);
                return fallback;
            }
            record(values, sources, key, hit[0], new ValueSource(Origin.valueOf(hit[1]), hit[2]));
            return Double.parseDouble(hit[0]);
        }

        long whole(String key, long fallback, java.util.function.LongPredicate valid) {
            String[] hit = find(key, t -> {
                try {
                    return valid.test(Long.parseLong(t));
                } catch (NumberFormatException e) {
                    return false;
                }
            });
            if (hit == null) {
                record(values, sources, key, Long.toString(fallback), null);
                return fallback;
            }
            record(values, sources, key, hit[0], new ValueSource(Origin.valueOf(hit[1]), hit[2]));
            return Long.parseLong(hit[0]);
        }
    }
}
