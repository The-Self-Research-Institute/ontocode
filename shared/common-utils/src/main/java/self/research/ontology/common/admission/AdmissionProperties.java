package self.research.ontology.common.admission;

import java.time.Duration;
import java.util.function.Function;

public record AdmissionProperties(
        double headroomFraction,
        double heapAfterGcRefuseRatio,
        double containerRefuseRatio,
        Duration maxWait,
        int maxWaiters,
        int maxActivePerOwner,
        int maxWaitingPerOwner,
        Duration watchdogMaxAge) {

    public static final String PREFIX = "ontocode.admission.";

    public static final double DEFAULT_HEADROOM_FRACTION = 0.25;
    public static final double DEFAULT_HEAP_AFTER_GC_REFUSE_RATIO = 0.75;
    public static final double DEFAULT_CONTAINER_REFUSE_RATIO = 0.85;
    public static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_WAITERS = 20;
    public static final int DEFAULT_MAX_ACTIVE_PER_OWNER = 1;
    public static final int DEFAULT_MAX_WAITING_PER_OWNER = 1;
    public static final Duration DEFAULT_WATCHDOG_MAX_AGE = Duration.ofMinutes(30);

    public AdmissionProperties {
        if (!(headroomFraction >= 0 && headroomFraction < 1)) {
            throw new IllegalArgumentException("headroomFraction must be in [0,1)");
        }
        if (!(heapAfterGcRefuseRatio > 0 && heapAfterGcRefuseRatio <= 1)) {
            throw new IllegalArgumentException("heapAfterGcRefuseRatio must be in (0,1]");
        }
        if (!(containerRefuseRatio > 0 && containerRefuseRatio <= 1)) {
            throw new IllegalArgumentException("containerRefuseRatio must be in (0,1]");
        }
        if (maxWait == null || maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must not be negative");
        }
        if (maxWaiters < 0 || maxWaitingPerOwner < 0) {
            throw new IllegalArgumentException("waiter limits must not be negative");
        }
        if (maxActivePerOwner < 1) {
            throw new IllegalArgumentException("maxActivePerOwner must be at least 1");
        }
        if (watchdogMaxAge == null || watchdogMaxAge.isNegative() || watchdogMaxAge.isZero()) {
            throw new IllegalArgumentException("watchdogMaxAge must be positive");
        }
    }

    public static AdmissionProperties defaults() {
        return new AdmissionProperties(DEFAULT_HEADROOM_FRACTION, DEFAULT_HEAP_AFTER_GC_REFUSE_RATIO,
                DEFAULT_CONTAINER_REFUSE_RATIO, DEFAULT_MAX_WAIT, DEFAULT_MAX_WAITERS,
                DEFAULT_MAX_ACTIVE_PER_OWNER, DEFAULT_MAX_WAITING_PER_OWNER, DEFAULT_WATCHDOG_MAX_AGE);
    }

    public static AdmissionProperties load() {
        return from(AdmissionProperties::systemOrEnv);
    }

    public static AdmissionProperties from(Function<String, String> lookup) {
        AdmissionProperties d = defaults();
        double headroom = number(lookup, "headroomFraction", d.headroomFraction());
        double heap = number(lookup, "heapAfterGcRefuseRatio", d.heapAfterGcRefuseRatio());
        double container = number(lookup, "containerRefuseRatio", d.containerRefuseRatio());
        long waitMillis = whole(lookup, "maxWaitMillis", d.maxWait().toMillis());
        long waiters = whole(lookup, "maxWaiters", d.maxWaiters());
        long active = whole(lookup, "maxActivePerOwner", d.maxActivePerOwner());
        long waitingPerOwner = whole(lookup, "maxWaitingPerOwner", d.maxWaitingPerOwner());
        long watchdogMinutes = whole(lookup, "watchdogMaxAgeMinutes", d.watchdogMaxAge().toMinutes());
        if (!(headroom >= 0 && headroom < 1)) {
            headroom = d.headroomFraction();
        }
        if (!(heap > 0 && heap <= 1)) {
            heap = d.heapAfterGcRefuseRatio();
        }
        if (!(container > 0 && container <= 1)) {
            container = d.containerRefuseRatio();
        }
        if (waitMillis < 0) {
            waitMillis = d.maxWait().toMillis();
        }
        if (waiters < 0 || waiters > Integer.MAX_VALUE) {
            waiters = d.maxWaiters();
        }
        if (active < 1 || active > Integer.MAX_VALUE) {
            active = d.maxActivePerOwner();
        }
        if (waitingPerOwner < 0 || waitingPerOwner > Integer.MAX_VALUE) {
            waitingPerOwner = d.maxWaitingPerOwner();
        }
        if (watchdogMinutes < 1) {
            watchdogMinutes = d.watchdogMaxAge().toMinutes();
        }
        return new AdmissionProperties(headroom, heap, container, Duration.ofMillis(waitMillis), (int) waiters,
                (int) active, (int) waitingPerOwner, Duration.ofMinutes(watchdogMinutes));
    }

    public static String environmentName(String key) {
        StringBuilder out = new StringBuilder("ONTOCODE_ADMISSION_");
        for (char c : key.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('_');
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }

    private static String systemOrEnv(String key) {
        String value = System.getProperty(PREFIX + key);
        if (value == null) {
            value = System.getenv(environmentName(key));
        }
        return value;
    }

    private static double number(Function<String, String> lookup, String key, double fallback) {
        String text = lookup.apply(key);
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long whole(Function<String, String> lookup, String key, long fallback) {
        String text = lookup.apply(key);
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
