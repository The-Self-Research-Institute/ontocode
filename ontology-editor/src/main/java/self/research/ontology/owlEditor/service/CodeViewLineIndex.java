package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
final class CodeViewLineIndex {

    static final int STRIDE = 1024;
    private static final ConcurrentHashMap<String, CodeViewLineIndex> CACHE = new ConcurrentHashMap<>();
    private static final int BUFFER_BYTES = 1 << 16;
    private static final int MAX_CACHED_FILES = 2048;

    private final long lastModified;
    private final long size;
    private final long[] checkpoints;
    private final long totalLines;
    private final boolean usable;

    private CodeViewLineIndex(long lastModified, long size, long[] checkpoints, long totalLines, boolean usable) {
        this.lastModified = lastModified;
        this.size = size;
        this.checkpoints = checkpoints;
        this.totalLines = totalLines;
        this.usable = usable;
    }

    static CodeViewLineIndex forFile(Path file, long lastModified, long size) throws IOException {
        String key = file.toString();
        CodeViewLineIndex cached = CACHE.get(key);
        if (cached != null && cached.describes(lastModified, size)) {
            return cached;
        }
        long started = System.nanoTime();
        CodeViewLineIndex built = build(file, lastModified, size);
        if (CACHE.size() >= MAX_CACHED_FILES) {
            CACHE.clear();
        }
        CACHE.put(key, built);
        log.info("[PERF] Indexed code view file {}: {} lines, {} bytes in {}ms", file.getFileName(), built.totalLines(),
                size, (System.nanoTime() - started) / 1_000_000);
        return built;
    }

    long totalLines() {
        return totalLines;
    }

    boolean usable() {
        return usable;
    }

    boolean describes(long fileLastModified, long fileSize) {
        return lastModified == fileLastModified && size == fileSize;
    }

    static CodeViewLineIndex build(Path file, long lastModified, long size) throws IOException {
        long[] checkpoints = new long[16];
        int checkpointCount = 1;
        long newlines = 0;
        long offset = 0;
        boolean previousWasCarriageReturn = false;
        boolean loneCarriageReturn = false;
        int lastByte = -1;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                for (int i = 0; i < read; i++) {
                    byte b = buffer[i];
                    if (previousWasCarriageReturn && b != '\n') {
                        loneCarriageReturn = true;
                    }
                    previousWasCarriageReturn = b == '\r';
                    if (b == '\n') {
                        newlines++;
                        if (newlines % STRIDE == 0) {
                            if (checkpointCount == checkpoints.length) {
                                checkpoints = Arrays.copyOf(checkpoints, checkpointCount * 2);
                            }
                            checkpoints[checkpointCount++] = offset + i + 1;
                        }
                    }
                    lastByte = b;
                }
                offset += read;
            }
        }
        if (previousWasCarriageReturn) {
            loneCarriageReturn = true;
        }
        long total = newlines + (size > 0 && lastByte != '\n' ? 1 : 0);
        return new CodeViewLineIndex(lastModified, size, Arrays.copyOf(checkpoints, checkpointCount), total,
                !loneCarriageReturn);
    }

    StorageManager.CodeViewPage readPage(Path file, long startLine, int lineCount) throws IOException {
        int checkpoint = (int) Math.min(startLine / STRIDE, checkpoints.length - 1L);
        long line = (long) checkpoint * STRIDE;
        StringBuilder page = new StringBuilder();
        int collected = 0;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(checkpoints[checkpoint]);
            BufferedReader reader = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8));
            String current;
            while (collected < lineCount && (current = reader.readLine()) != null) {
                if (line >= startLine) {
                    if (collected > 0) {
                        page.append('\n');
                    }
                    page.append(current);
                    collected++;
                }
                line++;
            }
        }
        return new StorageManager.CodeViewPage(page.toString(), startLine, collected, totalLines, size);
    }
}
