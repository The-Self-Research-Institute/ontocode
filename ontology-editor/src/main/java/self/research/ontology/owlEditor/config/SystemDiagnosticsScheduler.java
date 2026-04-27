package self.research.ontology.owlEditor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.pool.PoolStats;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Qualifier;

import jakarta.annotation.PostConstruct;
import java.lang.management.*;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Periodically logs JVM memory, GC, thread pool, and connection pool diagnostics.
 * <p>
 * Writes to the PERFORMANCE log so you can correlate slow requests
 * with heap pressure, GC pauses, thread pool exhaustion, or connection starvation.
 * <p>
 * Output example:
 *   [MEMORY] [OK] heap=1024/2048MB (50%) | non-heap=120MB | direct=45MB
 *   [GC] G1 Young: count=142 time=1230ms | G1 Old: count=2 time=890ms
 *   [THREADS] live=85 peak=102 daemon=78 | metadata-pool: active=3/16 queue=0
 *   [CONN-POOL] leased=5 available=15 pending=0 max=100
 */
@Component
public class SystemDiagnosticsScheduler {

    private static final Logger perfLog = LoggerFactory.getLogger("PERFORMANCE");
    private static final Logger log = LoggerFactory.getLogger(SystemDiagnosticsScheduler.class);

    private final Executor metadataExecutor;
    private final Executor sparqlExecutor;
    private final PoolingHttpClientConnectionManager connectionManager;

    public SystemDiagnosticsScheduler(
            @Qualifier("metadataExecutor") Executor metadataExecutor,
            @Qualifier("sparqlExecutor") Executor sparqlExecutor,
            PoolingHttpClientConnectionManager connectionManager) {
        this.metadataExecutor = metadataExecutor;
        this.sparqlExecutor = sparqlExecutor;
        this.connectionManager = connectionManager;
    }

    @PostConstruct
    public void logStartupDiagnostics() {
        Runtime rt = Runtime.getRuntime();
        perfLog.info("[STARTUP] JVM maxHeap={}MB processors={} javaVersion={}",
                rt.maxMemory() / (1024 * 1024),
                rt.availableProcessors(),
                System.getProperty("java.version"));
    }

    /**
     * Runs every 60 seconds — logs memory, GC, and thread pool stats.
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void logSystemDiagnostics() {
        logMemory();
        logGarbageCollection();
        logThreadPools();
        logConnectionPool();
    }

    private void logMemory() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();

        long heapUsedMB = heap.getUsed() / (1024 * 1024);
        long heapMaxMB = heap.getMax() / (1024 * 1024);
        int heapPercent = heapMaxMB > 0 ? (int) ((heapUsedMB * 100) / heapMaxMB) : 0;
        long nonHeapUsedMB = nonHeap.getUsed() / (1024 * 1024);

        // Direct (off-heap) buffer memory
        long directMB = 0;
        try {
            List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
            for (BufferPoolMXBean pool : pools) {
                if ("direct".equals(pool.getName())) {
                    directMB = pool.getMemoryUsed() / (1024 * 1024);
                }
            }
        } catch (Exception ignored) {}

        String tag = heapPercent >= 90 ? "CRITICAL" : heapPercent >= 75 ? "WARNING" : "OK";
        perfLog.info("[MEMORY] [{}] heap={}/ {}MB ({}%) | non-heap={}MB | direct={}MB",
                tag, heapUsedMB, heapMaxMB, heapPercent, nonHeapUsedMB, directMB);

        if (heapPercent >= 85) {
            perfLog.warn("[MEMORY] ALERT: Heap usage at {}% — risk of OOM or heavy GC. Consider increasing -Xmx.",
                    heapPercent);
        }
    }

    private void logGarbageCollection() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        StringBuilder sb = new StringBuilder("[GC]");
        for (GarbageCollectorMXBean gc : gcBeans) {
            sb.append(String.format(" %s: count=%d time=%dms |",
                    gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
        }
        // Remove trailing |
        if (sb.charAt(sb.length() - 1) == '|') {
            sb.setLength(sb.length() - 1);
        }
        perfLog.info(sb.toString());
    }

    private void logThreadPools() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[THREADS] live=%d peak=%d daemon=%d",
                threads.getThreadCount(), threads.getPeakThreadCount(), threads.getDaemonThreadCount()));

        // Log named executor pools if they are ThreadPoolTaskExecutor
        appendPoolStats(sb, "metadata-pool", metadataExecutor);
        appendPoolStats(sb, "sparql-pool", sparqlExecutor);

        perfLog.info(sb.toString());

        // Warn if any pool is near exhaustion
        warnIfPoolExhausted("metadata-pool", metadataExecutor);
        warnIfPoolExhausted("sparql-pool", sparqlExecutor);
    }

    private void appendPoolStats(StringBuilder sb, String name, Executor executor) {
        if (executor instanceof ThreadPoolTaskExecutor pool) {
            sb.append(String.format(" | %s: active=%d/%d queue=%d",
                    name,
                    pool.getActiveCount(),
                    pool.getMaxPoolSize(),
                    pool.getThreadPoolExecutor().getQueue().size()));
        }
    }

    private void warnIfPoolExhausted(String name, Executor executor) {
        if (executor instanceof ThreadPoolTaskExecutor pool) {
            int active = pool.getActiveCount();
            int max = pool.getMaxPoolSize();
            int queueSize = pool.getThreadPoolExecutor().getQueue().size();
            if (active >= max - 1) {
                perfLog.warn("[THREADS] ALERT: {} near exhaustion — active={}/{} queued={}",
                        name, active, max, queueSize);
            }
        }
    }

    private void logConnectionPool() {
        try {
            PoolStats stats = connectionManager.getTotalStats();
            int leased = stats.getLeased();
            int available = stats.getAvailable();
            int pending = stats.getPending();
            int max = stats.getMax();

            String tag = (leased >= max - 2) ? "WARNING" : "OK";
            perfLog.info("[CONN-POOL] [{}] leased={} available={} pending={} max={}",
                    tag, leased, available, pending, max);

            if (pending > 0) {
                perfLog.warn("[CONN-POOL] ALERT: {} requests waiting for a connection — possible starvation",
                        pending);
            }
            if (leased >= max - 2) {
                perfLog.warn("[CONN-POOL] ALERT: Connection pool near capacity — leased={}/{}",
                        leased, max);
            }
        } catch (Exception e) {
            log.debug("Failed to read connection pool stats: {}", e.getMessage());
        }
    }
}
