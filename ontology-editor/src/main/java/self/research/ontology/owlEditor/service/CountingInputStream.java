package self.research.ontology.owlEditor.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class CountingInputStream extends FilterInputStream {
    private long count = 0L;
    private long mark = -1L;

    CountingInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            count++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            count += n;
        }
        return n;
    }

    @Override
    public synchronized void mark(int readlimit) {
        if (in.markSupported()) {
            super.mark(readlimit);
            mark = count;
        }
    }

    @Override
    public synchronized void reset() throws IOException {
        if (in.markSupported()) {
            super.reset();
            if (mark >= 0) {
                count = mark;
            }
        }
    }

    long getCount() {
        return count;
    }
}
