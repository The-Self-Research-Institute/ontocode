package self.research.ontology.owlEditor.upload;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

public class BoundedGzipInputStream extends FilterInputStream {

    private final CountingInputStream compressed;
    private final ArchiveLimits limits;
    private long produced;

    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }

    private BoundedGzipInputStream(CountingInputStream counting, ArchiveLimits limits) throws IOException {
        super(new GZIPInputStream(counting));
        this.compressed = counting;
        this.limits = limits;
    }

    public static BoundedGzipInputStream open(InputStream raw, ArchiveLimits limits) throws IOException {
        return new BoundedGzipInputStream(new CountingInputStream(raw), limits);
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) {
            produced++;
            check();
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = in.read(buf, off, len);
        if (n > 0) {
            produced += n;
            check();
        }
        return n;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    private void check() throws UploadLimitExceededException {
        if (produced > limits.gzipMaxBytes()) {
            throw new UploadLimitExceededException(
                    "The compressed file expands to more than " + limits.gzipMaxBytes() / (1024 * 1024) + " MB",
                    limits.gzipMaxBytes());
        }
        if (produced > ArchiveLimits.RATIO_FLOOR_BYTES
                && produced > Math.max(1, compressed.count) * limits.gzipMaxRatio()) {
            throw new UploadLimitExceededException(
                    "The compressed file expands more than " + limits.gzipMaxRatio() + " times, which is not accepted",
                    limits.gzipMaxBytes());
        }
    }
}
