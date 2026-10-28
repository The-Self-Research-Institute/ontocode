package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import self.research.ontology.common.admission.RefusalReason;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ReasoningTaskRunner {

    public static final String BUSY_MESSAGE = "Too many reasoning requests in progress. Please try again shortly.";
    public static final long RETRY_AFTER_MILLIS = 1_000L;

    private static final Logger log = LoggerFactory.getLogger(ReasoningTaskRunner.class);

    private static final int NEW = 0;
    private static final int RUNNING = 1;
    private static final int DONE = 2;

    public interface Work<T> {
        T run(Context context) throws Exception;
    }

    public static final class Context {
        private final List<OWLReasoner> reasoners = new CopyOnWriteArrayList<>();
        private final List<Runnable> uncooperativeHandlers = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;

        public void attach(OWLReasoner reasoner) {
            if (reasoner == null) {
                return;
            }
            reasoners.add(reasoner);
            if (cancelled) {
                interruptQuietly(reasoner);
            }
        }

        public void onUncooperative(Runnable handler) {
            uncooperativeHandlers.add(handler);
        }

        public boolean cancelled() {
            return cancelled;
        }

        void cancel() {
            cancelled = true;
            for (OWLReasoner reasoner : reasoners) {
                interruptQuietly(reasoner);
            }
        }

        void uncooperative() {
            for (Runnable handler : uncooperativeHandlers) {
                try {
                    handler.run();
                } catch (RuntimeException e) {
                    log.debug("Uncooperative handler failed: {}", e.getMessage());
                }
            }
        }

        private static void interruptQuietly(OWLReasoner reasoner) {
            try {
                reasoner.interrupt();
            } catch (RuntimeException e) {
                log.debug("Reasoner interrupt failed: {}", e.getMessage());
            }
        }
    }

    public final class Execution<T> {
        private final String label;
        private final Context context;
        private final AtomicInteger state = new AtomicInteger(NEW);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final List<Runnable> afterStop = new CopyOnWriteArrayList<>();
        private volatile FutureTask<T> task;
        private volatile boolean uncooperative;
        private Thread worker;

        private Execution(String label, Context context) {
            this.label = label;
            this.context = context;
        }

        public T await(long timeoutMs) throws TimeoutException, ExecutionException, InterruptedException {
            try {
                return task.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                cancelAndWait();
                throw e;
            } catch (InterruptedException e) {
                cancelAndWait();
                throw e;
            }
        }

        public void cancel() {
            cancelAndWait();
        }

        public boolean stopped() {
            return finished.getCount() == 0;
        }

        public boolean uncooperative() {
            return uncooperative && !stopped();
        }

        public void whenStopped(Runnable callback) {
            afterStop.add(callback);
            if (stopped()) {
                drainCallbacks();
            }
        }

        private void cancelAndWait() {
            context.cancel();
            FutureTask<T> current = task;
            if (current != null) {
                current.cancel(true);
            }
            synchronized (this) {
                if (worker != null) {
                    worker.interrupt();
                }
            }
            if (state.compareAndSet(NEW, DONE)) {
                markStopped();
                return;
            }
            try {
                if (finished.await(stopGraceMs, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!stopped() && !uncooperative) {
                uncooperative = true;
                uncooperativeCount.incrementAndGet();
                log.warn("Reasoning task '{}' ignored the stop request after {} ms and is still running; "
                        + "its memory stays reserved until it ends", label, stopGraceMs);
                context.uncooperative();
            }
        }

        private void markStopped() {
            if (uncooperative) {
                uncooperative = false;
                uncooperativeCount.decrementAndGet();
                log.info("Reasoning task '{}' finally stopped; its memory reservation can now be released", label);
            }
            finished.countDown();
            drainCallbacks();
        }

        private void drainCallbacks() {
            for (Runnable callback : afterStop) {
                if (afterStop.remove(callback)) {
                    try {
                        callback.run();
                    } catch (RuntimeException e) {
                        log.warn("Cleanup after reasoning task '{}' failed: {}", label, e.getMessage());
                    }
                }
            }
        }
    }

    private final ThreadPoolExecutor pool;
    private final long stopGraceMs;
    private final AtomicInteger uncooperativeCount = new AtomicInteger();

    @Autowired
    public ReasoningTaskRunner(
            @Value("${ontocode.reasoner.task-threads:4}") int threads,
            @Value("${ontocode.reasoner.task-queue:8}") int queue,
            @Value("${ontocode.reasoner.stop-grace-ms:3000}") long stopGraceMs) {
        this(threads, queue, stopGraceMs, "reasoning-task-worker");
    }

    public ReasoningTaskRunner(int threads, int queue, long stopGraceMs, String threadName) {
        this.stopGraceMs = Math.max(0, stopGraceMs);
        this.pool = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queue)), r -> {
                    Thread t = new Thread(r, threadName);
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(true);
    }

    public static ReasoningTaskRunner standalone() {
        return new ReasoningTaskRunner(4, 8, 3_000L, "reasoning-task-worker");
    }

    public <T> Execution<T> submit(String label, Work<T> work) {
        Context context = new Context();
        Execution<T> execution = new Execution<>(label, context);
        Callable<T> body = () -> {
            if (!execution.state.compareAndSet(NEW, RUNNING)) {
                return null;
            }
            synchronized (execution) {
                execution.worker = Thread.currentThread();
            }
            try {
                return work.run(context);
            } finally {
                synchronized (execution) {
                    execution.worker = null;
                }
                execution.state.set(DONE);
                execution.markStopped();
            }
        };
        FutureTask<T> task = new FutureTask<>(body);
        execution.task = task;
        try {
            pool.execute(task);
        } catch (RejectedExecutionException e) {
            throw busy();
        }
        return execution;
    }

    public <T> T call(String label, long timeoutMs, Work<T> work) throws Exception {
        Execution<T> execution = submit(label, work);
        try {
            return execution.await(timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    public static void afterAll(List<? extends Execution<?>> executions, Runnable cleanup) {
        List<Execution<?>> pending = new java.util.ArrayList<>();
        for (Execution<?> execution : executions) {
            if (!execution.stopped()) {
                pending.add(execution);
            }
        }
        if (pending.isEmpty()) {
            cleanup.run();
            return;
        }
        AtomicInteger remaining = new AtomicInteger(pending.size());
        for (Execution<?> execution : pending) {
            execution.whenStopped(() -> {
                if (remaining.decrementAndGet() == 0) {
                    cleanup.run();
                }
            });
        }
    }

    public int uncooperativeTasks() {
        return uncooperativeCount.get();
    }

    public int activeThreads() {
        return pool.getPoolSize();
    }

    public int queued() {
        return pool.getQueue().size();
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    public static BusyException busy() {
        return new BusyException(RefusalReason.QUEUE_FULL, BUSY_MESSAGE, RETRY_AFTER_MILLIS);
    }
}
