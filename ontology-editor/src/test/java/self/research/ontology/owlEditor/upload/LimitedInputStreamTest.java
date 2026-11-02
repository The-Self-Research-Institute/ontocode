package self.research.ontology.owlEditor.upload;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LimitedInputStreamTest {

    private static InputStream trickle(byte[] data, int step) {
        ByteArrayInputStream base = new ByteArrayInputStream(data);
        return new InputStream() {
            @Override
            public int read() {
                return base.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return base.read(b, off, Math.min(len, step));
            }
        };
    }

    @Test
    void exactlyTheLimitIsAccepted() throws IOException {
        LimitedInputStream in = new LimitedInputStream(new ByteArrayInputStream(new byte[100]), 100);
        assertEquals(100, in.readAllBytes().length);
        assertEquals(100, in.bytesRead());
    }

    @Test
    void oneByteOverTheLimitIsRejected() {
        LimitedInputStream in = new LimitedInputStream(new ByteArrayInputStream(new byte[101]), 100);
        UploadLimitExceededException e = assertThrows(UploadLimitExceededException.class, in::readAllBytes);
        assertEquals(100, e.getLimitBytes());
    }

    @Test
    void singleByteReadsAreCounted() throws IOException {
        LimitedInputStream in = new LimitedInputStream(new ByteArrayInputStream(new byte[3]), 2);
        in.read();
        in.read();
        assertThrows(UploadLimitExceededException.class, in::read);
    }

    @Test
    void chunkedStyleSmallReadsAreCountedWithoutAnyDeclaredLength() {
        LimitedInputStream in = new LimitedInputStream(trickle(new byte[5000], 7), 1000);
        assertThrows(UploadLimitExceededException.class, in::readAllBytes);
        assertEquals(1001, in.bytesRead());
    }

    @Test
    void neverReadsMoreThanOneByteBeyondTheLimit() {
        LimitedInputStream in = new LimitedInputStream(new ByteArrayInputStream(new byte[10_000]), 10);
        byte[] buf = new byte[4096];
        assertThrows(UploadLimitExceededException.class, () -> {
            while (in.read(buf, 0, buf.length) >= 0) {
            }
        });
        assertEquals(11, in.bytesRead());
    }

    @Test
    void skipIsCounted() {
        LimitedInputStream in = new LimitedInputStream(new ByteArrayInputStream(new byte[50]), 20);
        assertThrows(UploadLimitExceededException.class, () -> in.skip(50));
    }
}
