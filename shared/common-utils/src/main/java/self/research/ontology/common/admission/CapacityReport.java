package self.research.ontology.common.admission;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record CapacityReport(
        Instant generatedAt,
        long capacityBytes,
        long baselineBytes,
        long reservedBytes,
        long peakReservedBytes,
        double headroomFraction,
        List<KindReport> kinds,
        Map<String, Long> cacheAccounts,
        MemorySignals.Report signals,
        LedgerStats.Snapshot totals,
        Map<String, String> config,
        Map<String, String> configSources,
        List<ReservationLedger.Leak> oldReservations,
        Duration oldReservationThreshold) {

    public record KindReport(
            String kind,
            List<String> services,
            int active,
            int waiting,
            long reservedBytes,
            long shareCapBytes,
            double shareCapFraction,
            double shareUsedOfCapacity,
            LedgerStats.KindSnapshot counters) {

        public double shareUsedOfCap() {
            return shareCapBytes <= 0 ? 0.0 : (double) reservedBytes / shareCapBytes;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", generatedAt.toString());
        out.put("capacityBytes", capacityBytes);
        out.put("baselineBytes", baselineBytes);
        out.put("reservedBytes", reservedBytes);
        out.put("peakReservedBytes", peakReservedBytes);
        out.put("headroomFraction", headroomFraction);
        List<Object> kindList = new ArrayList<>();
        for (KindReport k : kinds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", k.kind());
            m.put("services", k.services());
            m.put("active", k.active());
            m.put("waiting", k.waiting());
            m.put("reservedBytes", k.reservedBytes());
            m.put("shareCapBytes", k.shareCapBytes());
            m.put("shareCapFraction", k.shareCapFraction());
            m.put("shareUsedOfCap", k.shareUsedOfCap());
            m.put("offered", k.counters().offered());
            m.put("admitted", k.counters().admitted());
            m.put("completed", k.counters().completed());
            Map<String, Long> refused = new TreeMap<>();
            k.counters().refusedByReason().forEach((r, c) -> refused.put(r.name(), c));
            m.put("refusedByReason", refused);
            m.put("waitCount", k.counters().waitCount());
            m.put("waitSumMillis", k.counters().waitSumNanos() / 1_000_000);
            m.put("waitMaxMillis", k.counters().waitMaxNanos() / 1_000_000);
            kindList.add(m);
        }
        out.put("kinds", kindList);
        out.put("cacheAccounts", cacheAccounts);
        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("containerSource", signals.containerSource());
        sig.put("containerLimit", metric(signals.containerLimit()));
        sig.put("containerUsageRaw", metric(signals.containerUsageRaw()));
        sig.put("containerUsageAdjusted", metric(signals.containerUsageAdjusted()));
        sig.put("heapMaxBytes", signals.heapMaxBytes());
        sig.put("heapUsedBytes", signals.heapUsedBytes());
        sig.put("heapAfterGcBytes", signals.heapAfterGcBytes());
        sig.put("afterGcAvailable", signals.afterGcAvailable());
        sig.put("directBufferBytes", metric(signals.directBufferBytes()));
        sig.put("mappedBufferBytes", metric(signals.mappedBufferBytes()));
        out.put("signals", sig);
        Map<String, Object> totalsMap = new LinkedHashMap<>();
        totalsMap.put("offered", totals.offered());
        totalsMap.put("admitted", totals.admitted());
        totalsMap.put("completed", totals.completed());
        totalsMap.put("refusedTotal", totals.refusedTotal());
        totalsMap.put("activeCount", totals.activeCount());
        totalsMap.put("waitingNow", totals.waitingNow());
        totalsMap.put("doubleReleaseAttempts", totals.doubleReleaseAttempts());
        out.put("totals", totalsMap);
        out.put("config", config);
        out.put("configSources", configSources);
        List<Object> old = new ArrayList<>();
        for (ReservationLedger.Leak leak : oldReservations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", leak.id());
            m.put("owner", leak.owner());
            m.put("kind", leak.kind());
            m.put("bytes", leak.bytes());
            m.put("ageSeconds", leak.age().toSeconds());
            old.add(m);
        }
        out.put("oldReservations", old);
        out.put("oldReservationThresholdSeconds", oldReservationThreshold.toSeconds());
        return out;
    }

    private static Map<String, Object> metric(MemorySignals.Metric m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", m.status().name());
        out.put("bytes", m.isAvailable() ? (Object) m.bytes() : null);
        return out;
    }

    public String renderTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("capacity ").append(mb(capacityBytes)).append("  baseline ").append(mb(baselineBytes))
                .append("  reserved ").append(mb(reservedBytes)).append("  peak ").append(mb(peakReservedBytes))
                .append("  headroom ").append(headroomFraction).append('\n');
        sb.append(String.format("%-18s %6s %7s %10s %10s %6s %8s %8s %8s%n", "kind", "active", "waiting",
                "reservedMB", "shareCapMB", "share%", "admitted", "refused", "waitMaxMs"));
        for (KindReport k : kinds) {
            sb.append(String.format("%-18s %6d %7d %10s %10s %6.0f %8d %8d %8d%n", k.kind(), k.active(), k.waiting(),
                    mb(k.reservedBytes()), mb(k.shareCapBytes()), k.shareUsedOfCap() * 100,
                    k.counters().admitted(), k.counters().refusedTotal(), k.counters().waitMaxNanos() / 1_000_000));
        }
        sb.append("caches:");
        if (cacheAccounts.isEmpty()) {
            sb.append(" none");
        }
        cacheAccounts.forEach((n, b) -> sb.append(' ').append(n).append('=').append(mb(b)));
        sb.append('\n').append(signals).append('\n');
        if (!oldReservations.isEmpty()) {
            sb.append("reservations older than ").append(oldReservationThreshold.toMinutes()).append(" min:\n");
            for (ReservationLedger.Leak leak : oldReservations) {
                sb.append("  #").append(leak.id()).append(' ').append(leak.owner()).append(' ').append(leak.kind())
                        .append(' ').append(mb(leak.bytes())).append(" age ").append(leak.age().toMinutes())
                        .append(" min\n");
            }
        }
        sb.append("config:\n");
        config.forEach((k, v) -> sb.append("  ").append(k).append('=').append(v).append("  [")
                .append(configSources.get(k)).append("]\n"));
        return sb.toString();
    }

    private static String mb(long bytes) {
        if (bytes == Long.MAX_VALUE) {
            return "unbounded";
        }
        return (bytes / (1024 * 1024)) + "MB";
    }
}
