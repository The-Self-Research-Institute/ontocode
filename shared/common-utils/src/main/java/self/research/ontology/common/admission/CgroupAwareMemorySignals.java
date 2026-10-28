package self.research.ontology.common.admission;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CgroupAwareMemorySignals implements MemorySignals {

    public static final Path DEFAULT_ROOT = Path.of("/sys/fs/cgroup");
    private static final long V1_UNLIMITED_THRESHOLD = 1L << 62;

    private enum Source { V2, V1, NONE }

    private final Path root;

    public CgroupAwareMemorySignals() {
        this(DEFAULT_ROOT);
    }

    public CgroupAwareMemorySignals(Path root) {
        this.root = root;
    }

    private Source source() {
        if (Files.exists(root.resolve("memory.max")) || Files.exists(root.resolve("memory.current"))) {
            return Source.V2;
        }
        if (Files.exists(root.resolve("memory").resolve("memory.limit_in_bytes"))
                || Files.exists(root.resolve("memory").resolve("memory.usage_in_bytes"))) {
            return Source.V1;
        }
        return Source.NONE;
    }

    @Override
    public String containerSource() {
        return switch (source()) {
            case V2 -> "cgroup-v2";
            case V1 -> "cgroup-v1";
            case NONE -> "none";
        };
    }

    @Override
    public Metric containerLimit() {
        return switch (source()) {
            case V2 -> parseLimit(readTrimmed(root.resolve("memory.max")), false);
            case V1 -> parseLimit(readTrimmed(root.resolve("memory").resolve("memory.limit_in_bytes")), true);
            case NONE -> Metric.unavailable();
        };
    }

    @Override
    public Metric containerUsageRaw() {
        return switch (source()) {
            case V2 -> parseUsage(readTrimmed(root.resolve("memory.current")));
            case V1 -> parseUsage(readTrimmed(root.resolve("memory").resolve("memory.usage_in_bytes")));
            case NONE -> Metric.unavailable();
        };
    }

    @Override
    public Metric containerUsageAdjusted() {
        Metric raw = containerUsageRaw();
        if (!raw.isAvailable()) {
            return raw;
        }
        Source source = source();
        Path stat = source == Source.V1 ? root.resolve("memory").resolve("memory.stat") : root.resolve("memory.stat");
        String key = source == Source.V1 ? "total_inactive_file" : "inactive_file";
        long inactive = readStatValue(stat, key);
        if (inactive < 0) {
            return raw;
        }
        return Metric.available(Math.max(0, raw.bytes() - inactive));
    }

    @Override
    public long heapMaxBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    }

    @Override
    public long heapUsedBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    @Override
    public HeapAfterGc heapAfterGc() {
        long sum = 0;
        boolean any = false;
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean pool : pools) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            MemoryUsage usage = pool.getCollectionUsage();
            if (usage == null) {
                continue;
            }
            any = true;
            sum += Math.max(0, usage.getUsed());
        }
        if (!any || sum <= 0) {
            return new HeapAfterGc(heapUsedBytes(), false);
        }
        return new HeapAfterGc(sum, true);
    }

    @Override
    public Metric directBufferBytes() {
        return bufferPool("direct");
    }

    @Override
    public Metric mappedBufferBytes() {
        return bufferPool("mapped");
    }

    private static Metric bufferPool(String name) {
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (name.equals(pool.getName())) {
                long used = pool.getMemoryUsed();
                return used < 0 ? Metric.unavailable() : Metric.available(used);
            }
        }
        return Metric.unavailable();
    }

    static Metric parseLimit(String text, boolean v1) {
        if (text == null || text.isEmpty()) {
            return Metric.unavailable();
        }
        if (text.equals("max")) {
            return Metric.unlimited();
        }
        Long value = parseLong(text);
        if (value == null || value <= 0) {
            return Metric.unavailable();
        }
        if (v1 && value >= V1_UNLIMITED_THRESHOLD) {
            return Metric.unlimited();
        }
        return Metric.available(value);
    }

    static Metric parseUsage(String text) {
        Long value = parseLong(text);
        if (value == null || value < 0) {
            return Metric.unavailable();
        }
        return Metric.available(value);
    }

    private static Long parseLong(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readTrimmed(Path path) {
        try {
            return Files.readString(path).trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static long readStatValue(Path stat, String key) {
        String content = readTrimmed(stat);
        if (content == null) {
            return -1;
        }
        for (String line : content.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[0].equals(key)) {
                Long value = parseLong(parts[1]);
                return value == null || value < 0 ? -1 : value;
            }
        }
        return -1;
    }
}
