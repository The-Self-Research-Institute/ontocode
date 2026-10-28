package self.research.ontology.owlEditor.upload;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SemaphoreUploadAdmission implements UploadAdmission {

    private final Semaphore permits;
    private final int max;
    private final long waitMs;

    @Autowired
    public SemaphoreUploadAdmission(UploadSettings settings) {
        this(settings.maxConcurrent(), settings.maxWaitMs());
    }

    public SemaphoreUploadAdmission(int max, long waitMs) {
        this.max = Math.max(1, max);
        this.waitMs = Math.max(0, waitMs);
        this.permits = new Semaphore(this.max, true);
    }

    @Override
    public Lease tryAdmit() throws InterruptedException {
        if (!permits.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
            return null;
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        };
    }

    @Override
    public int retryAfterSeconds() {
        return 10;
    }

    public int available() {
        return permits.availablePermits();
    }

    public int max() {
        return max;
    }
}
