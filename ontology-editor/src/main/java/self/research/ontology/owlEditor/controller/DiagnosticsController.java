package self.research.ontology.owlEditor.controller;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.pool.PoolStats;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private final Executor metadataExecutor;
    private final Executor sparqlExecutor;
    private final PoolingHttpClientConnectionManager connectionManager;

    public DiagnosticsController(
            @Qualifier("metadataExecutor") Executor metadataExecutor,
            @Qualifier("sparqlExecutor") Executor sparqlExecutor,
            PoolingHttpClientConnectionManager connectionManager) {
        this.metadataExecutor = metadataExecutor;
        this.sparqlExecutor = sparqlExecutor;
        this.connectionManager = connectionManager;
    }

    @GetMapping
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", System.currentTimeMillis());
        result.put("memory", getMemoryStats());
        result.put("gc", getGcStats());
        result.put("threads", getThreadStats());
        result.put("threadPools", getThreadPoolStats());
        result.put("connectionPool", getConnectionPoolStats());
        result.put("diagnosis", getDiagnosis());
        return result;
    }

    private Map<String, Object> getMemoryStats() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();
        Runtime rt = Runtime.getRuntime();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heapUsedMB", heap.getUsed() / (1024 * 1024));
        m.put("heapMaxMB", heap.getMax() / (1024 * 1024));
        m.put("heapPercent", heap.getMax() > 0 ? (int) ((heap.getUsed() * 100) / heap.getMax()) : 0);
        m.put("nonHeapUsedMB", nonHeap.getUsed() / (1024 * 1024));
        m.put("availableProcessors", rt.availableProcessors());

        try {
            for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if ("direct".equals(pool.getName())) {
                    m.put("directBufferMB", pool.getMemoryUsed() / (1024 * 1024));
                    m.put("directBufferCount", pool.getCount());
                }
            }
        } catch (Exception ignored) {}

        return m;
    }

    private List<Map<String, Object>> getGcStats() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", gc.getName());
            entry.put("collectionCount", gc.getCollectionCount());
            entry.put("collectionTimeMs", gc.getCollectionTime());
            list.add(entry);
        }
        return list;
    }

    private Map<String, Object> getThreadStats() {
        ThreadMXBean t = ManagementFactory.getThreadMXBean();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("liveThreads", t.getThreadCount());
        m.put("peakThreads", t.getPeakThreadCount());
        m.put("daemonThreads", t.getDaemonThreadCount());
        m.put("totalStarted", t.getTotalStartedThreadCount());
        return m;
    }

    private Map<String, Object> getThreadPoolStats() {
        Map<String, Object> pools = new LinkedHashMap<>();
        pools.put("metadataPool", describePool(metadataExecutor));
        pools.put("sparqlPool", describePool(sparqlExecutor));
        return pools;
    }

    private Map<String, Object> describePool(Executor executor) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (executor instanceof ThreadPoolTaskExecutor pool) {
            m.put("activeThreads", pool.getActiveCount());
            m.put("corePoolSize", pool.getCorePoolSize());
            m.put("maxPoolSize", pool.getMaxPoolSize());
            m.put("poolSize", pool.getPoolSize());
            m.put("queueSize", pool.getThreadPoolExecutor().getQueue().size());
            m.put("queueCapacity", pool.getThreadPoolExecutor().getQueue().remainingCapacity()
                    + pool.getThreadPoolExecutor().getQueue().size());
            m.put("completedTasks", pool.getThreadPoolExecutor().getCompletedTaskCount());
            m.put("status", pool.getActiveCount() >= pool.getMaxPoolSize() - 1 ? "NEAR_EXHAUSTION" : "OK");
        } else {
            m.put("type", executor.getClass().getSimpleName());
            m.put("status", "UNKNOWN");
        }
        return m;
    }

    private Map<String, Object> getConnectionPoolStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            PoolStats stats = connectionManager.getTotalStats();
            m.put("leased", stats.getLeased());
            m.put("available", stats.getAvailable());
            m.put("pending", stats.getPending());
            m.put("max", stats.getMax());
            m.put("status", stats.getPending() > 0 ? "WAITING" :
                    stats.getLeased() >= stats.getMax() - 2 ? "NEAR_CAPACITY" : "OK");
        } catch (Exception e) {
            m.put("error", e.getMessage());
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getDiagnosis() {
        Map<String, Object> d = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();

        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        int heapPct = heap.getMax() > 0 ? (int) ((heap.getUsed() * 100) / heap.getMax()) : 0;
        if (heapPct >= 90) {
            issues.add("CRITICAL: Heap at " + heapPct + "% — likely OOM soon. Increase -Xmx or reduce load.");
        } else if (heapPct >= 75) {
            issues.add("WARNING: Heap at " + heapPct + "% — approaching limit. Monitor closely.");
        }

        long totalGcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            totalGcTime += gc.getCollectionTime();
        }
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        if (uptimeMs > 0) {
            double gcPercent = (totalGcTime * 100.0) / uptimeMs;
            if (gcPercent > 10) {
                issues.add("CRITICAL: GC overhead " + String.format("%.1f", gcPercent) + "% of uptime — memory pressure.");
            } else if (gcPercent > 5) {
                issues.add("WARNING: GC overhead " + String.format("%.1f", gcPercent) + "% of uptime.");
            }
            d.put("gcOverheadPercent", String.format("%.2f", gcPercent));
        }

        checkPoolExhaustion(issues, "metadataPool", metadataExecutor);
        checkPoolExhaustion(issues, "sparqlPool", sparqlExecutor);

        try {
            PoolStats stats = connectionManager.getTotalStats();
            if (stats.getPending() > 0) {
                issues.add("WARNING: " + stats.getPending() + " HTTP requests waiting for connections.");
            }
        } catch (Exception ignored) {}

        d.put("issues", issues);
        d.put("verdict", issues.isEmpty() ? "HEALTHY" :
                issues.stream().anyMatch(s -> s.startsWith("CRITICAL")) ? "CRITICAL" : "DEGRADED");
        return d;
    }

    private void checkPoolExhaustion(List<String> issues, String name, Executor executor) {
        if (executor instanceof ThreadPoolTaskExecutor pool) {
            if (pool.getActiveCount() >= pool.getMaxPoolSize() - 1) {
                issues.add("WARNING: " + name + " thread pool near exhaustion — active="
                        + pool.getActiveCount() + "/" + pool.getMaxPoolSize()
                        + " queued=" + pool.getThreadPoolExecutor().getQueue().size());
            }
        }
    }
}
