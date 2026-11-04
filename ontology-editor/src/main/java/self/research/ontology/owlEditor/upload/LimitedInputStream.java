package self.research.ontology.owlEditor.upload;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class LimitedInputStream extends FilterInputStream {

    private final long limit;
    private long count;

    public LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    public long bytesRead() {
        return count;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) {
            count++;
            check();
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        long room = limit - count + 1;
        int ask = (int) Math.min(len, Math.max(1, room));
        int n = in.read(buf, off, ask);
        if (n > 0) {
            count += n;
            check();
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        byte[] scratch = new byte[(int) Math.min(8192, n)];
        long skipped = 0;
        while (skipped < n) {
            int r = read(scratch, 0, (int) Math.min(scratch.length, n - skipped));
            if (r < 0) {
                break;
            }
            skipped += r;
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public synchronized void mark(int readlimit) {
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    private void check() throws UploadLimitExceededException {
        if (count > limit) {
            throw new UploadLimitExceededException("Upload is larger than the allowed " + limit + " bytes", limit);
        }
    }
}
