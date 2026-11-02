package self.research.ontology.owlEditor.upload;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemaphoreUploadAdmissionTest {

    @Test
    void refusesBeyondTheMaximumAndAdmitsAgainAfterRelease() throws Exception {
        SemaphoreUploadAdmission admission = new SemaphoreUploadAdmission(2, 20);
        UploadAdmission.Lease a = admission.tryAdmit();
        UploadAdmission.Lease b = admission.tryAdmit();
        assertNotNull(a);
        assertNotNull(b);
        assertNull(admission.tryAdmit());
        a.close();
        assertNotNull(admission.tryAdmit());
        assertEquals(0, admission.available());
    }

    @Test
    void closingTwiceReleasesOnlyOnce() throws Exception {
        SemaphoreUploadAdmission admission = new SemaphoreUploadAdmission(1, 10);
        UploadAdmission.Lease lease = admission.tryAdmit();
        lease.close();
        lease.close();
        assertEquals(1, admission.available());
    }

    @Test
    void neverMoreThanTheMaximumRunAtOnceUnderConcurrency() throws Exception {
        SemaphoreUploadAdmission admission = new SemaphoreUploadAdmission(3, 5000);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger admitted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch done = new CountDownLatch(12);
        for (int i = 0; i < 12; i++) {
            pool.submit(() -> {
                try {
                    UploadAdmission.Lease lease = admission.tryAdmit();
                    if (lease != null) {
                        admitted.incrementAndGet();
                        peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                        Thread.sleep(30);
                        running.decrementAndGet();
                        lease.close();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertTrue(peak.get() <= 3, "peak " + peak.get());
        assertEquals(12, admitted.get());
        assertEquals(3, admission.available());
    }
}
