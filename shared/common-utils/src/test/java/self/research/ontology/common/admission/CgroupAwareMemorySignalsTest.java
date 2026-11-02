package self.research.ontology.common.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import self.research.ontology.common.admission.MemorySignals.Metric;
import self.research.ontology.common.admission.MemorySignals.Status;

class CgroupAwareMemorySignalsTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private CgroupAwareMemorySignals signals() {
        return new CgroupAwareMemorySignals(root);
    }

    @Test
    void v2LimitAndUsage() throws IOException {
        write("memory.max", "2147483648\n");
        write("memory.current", "1073741824\n");
        CgroupAwareMemorySignals s = signals();
        assertEquals("cgroup-v2", s.containerSource());
        assertEquals(Metric.available(2147483648L), s.containerLimit());
        assertEquals(Metric.available(1073741824L), s.containerUsageRaw());
        assertEquals(1073741824L, s.containerUsageAdjusted().bytes());
        assertEquals(0.5, s.containerUsageRatio().getAsDouble(), 1e-9);
    }

    @Test
    void v2AdjustedSubtractsInactiveFile() throws IOException {
        write("memory.max", "1000");
        write("memory.current", "900");
        write("memory.stat", "anon 100\ninactive_file 300\nactive_file 5\n");
        CgroupAwareMemorySignals s = signals();
        assertEquals(900, s.containerUsageRaw().bytes());
        assertEquals(600, s.containerUsageAdjusted().bytes());
        write("memory.stat", "inactive_file 5000\n");
        assertEquals(0, s.containerUsageAdjusted().bytes());
        assertEquals(Status.AVAILABLE, s.containerUsageAdjusted().status());
    }

    @Test
    void adjustedFallsBackToRawWhenStatMissingOrGarbled() throws IOException {
        write("memory.max", "1000");
        write("memory.current", "900");
        assertEquals(900, signals().containerUsageAdjusted().bytes());
        write("memory.stat", "inactive_file abc\n");
        assertEquals(900, signals().containerUsageAdjusted().bytes());
    }

    @Test
    void v2MaxMeansUnlimitedNotZero() throws IOException {
        write("memory.max", "max\n");
        write("memory.current", "123");
        CgroupAwareMemorySignals s = signals();
        assertEquals(Status.UNLIMITED, s.containerLimit().status());
        assertTrue(s.containerLimit().isUnlimited());
        assertTrue(s.containerUsageRatio().isEmpty());
        assertEquals(123, s.containerUsageRaw().bytes());
    }

    @Test
    void missingFilesAreUnavailableNeverZero() {
        CgroupAwareMemorySignals s = signals();
        assertEquals("none", s.containerSource());
        assertEquals(Status.UNAVAILABLE, s.containerLimit().status());
        assertEquals(Status.UNAVAILABLE, s.containerUsageRaw().status());
        assertEquals(Status.UNAVAILABLE, s.containerUsageAdjusted().status());
        assertEquals(-1, s.containerLimit().bytes());
        assertTrue(s.containerUsageRatio().isEmpty());
    }

    @Test
    void v2MissingUsageFileOnly() throws IOException {
        write("memory.max", "1000");
        assertEquals(Status.UNAVAILABLE, signals().containerUsageRaw().status());
        assertEquals(Status.AVAILABLE, signals().containerLimit().status());
    }

    @Test
    void garbledAndNegativeValuesAreUnavailable() throws IOException {
        write("memory.max", "lots");
        write("memory.current", "-5");
        CgroupAwareMemorySignals s = signals();
        assertEquals(Status.UNAVAILABLE, s.containerLimit().status());
        assertEquals(Status.UNAVAILABLE, s.containerUsageRaw().status());
        write("memory.max", "-1");
        write("memory.current", "");
        assertEquals(Status.UNAVAILABLE, s.containerLimit().status());
        assertEquals(Status.UNAVAILABLE, s.containerUsageRaw().status());
        write("memory.max", "0");
        assertEquals(Status.UNAVAILABLE, s.containerLimit().status());
        write("memory.current", "99999999999999999999999");
        assertEquals(Status.UNAVAILABLE, s.containerUsageRaw().status());
    }

    @Test
    void zeroUsageIsARealReadingWhenFileSaysZero() throws IOException {
        write("memory.max", "1000");
        write("memory.current", "0");
        assertEquals(Metric.available(0), signals().containerUsageRaw());
    }

    @Test
    void v1LimitUsageAndAdjusted() throws IOException {
        write("memory/memory.limit_in_bytes", "536870912\n");
        write("memory/memory.usage_in_bytes", "268435456\n");
        write("memory/memory.stat", "cache 5\ntotal_inactive_file 1000\n");
        CgroupAwareMemorySignals s = signals();
        assertEquals("cgroup-v1", s.containerSource());
        assertEquals(536870912L, s.containerLimit().bytes());
        assertEquals(268435456L, s.containerUsageRaw().bytes());
        assertEquals(268435456L - 1000, s.containerUsageAdjusted().bytes());
    }

    @Test
    void v1HugeSentinelIsUnlimited() throws IOException {
        write("memory/memory.limit_in_bytes", "9223372036854771712");
        write("memory/memory.usage_in_bytes", "100");
        assertEquals(Status.UNLIMITED, signals().containerLimit().status());
    }

    @Test
    void v2WinsWhenBothPresent() throws IOException {
        write("memory.max", "1000");
        write("memory.current", "10");
        write("memory/memory.limit_in_bytes", "5");
        assertEquals(1000, signals().containerLimit().bytes());
    }

    @Test
    void jvmSignalsAreSeparatedAndSane() {
        CgroupAwareMemorySignals s = signals();
        assertTrue(s.heapUsedBytes() > 0);
        MemorySignals.HeapAfterGc afterGc = s.heapAfterGc();
        assertTrue(afterGc.bytes() > 0);
        MemorySignals.Metric direct = s.directBufferBytes();
        assertTrue(direct.isAvailable() ? direct.bytes() >= 0 : direct.bytes() == -1);
        MemorySignals.Report report = s.report();
        assertFalse(report.toString().isBlank());
        assertEquals(afterGc.afterGcAvailable(), report.afterGcAvailable() || !afterGc.afterGcAvailable());
    }
}
