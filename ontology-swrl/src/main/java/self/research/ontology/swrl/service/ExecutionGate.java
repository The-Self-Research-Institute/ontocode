package self.research.ontology.swrl.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ExecutionGate {

    public static final String QUEUE_FULL = "queue_full";
    public static final String OWNER_LIMIT = "owner_limit";
    public static final String WAIT_TIMEOUT = "wait_timeout";

    public record Stats(int workers, int queueCapacity, int active, int queued, long refusedQueueFull,
                        long refusedOwnerLimit, long refusedWaitTimeout, long completed, long longestRunningMillis) {
        public long refused() {
            return refusedQueueFull + refusedOwnerLimit + refusedWaitTimeout;
        }
    }

    private final ThreadPoolExecutor pool;
    private final int workers;
    private final int queueCapacity;
    private final int perOwnerActive;
    private final int perOwnerWaiting;
    private final long maxWaitMillis;
    private final Object lock = new Object();
    private final Map<String, Integer> running = new HashMap<>();
    private final Map<String, Integer> waiting = new HashMap<>();
    private final Map<Long, Long> runningSince = new HashMap<>();
    private final AtomicLong taskIds = new AtomicLong();
    private final AtomicLong refusedQueueFull = new AtomicLong();
    private final AtomicLong refusedOwnerLimit = new AtomicLong();
    private final AtomicLong refusedWaitTimeout = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();

    public ExecutionGate(int workers, int queueCapacity, int perOwnerActive, int perOwnerWaiting, long maxWaitMillis) {
        this.workers = Math.max(1, workers);
        this.queueCapacity = Math.max(1, queueCapacity);
        this.perOwnerActive = Math.max(1, perOwnerActive);
        this.perOwnerWaiting = Math.max(0, perOwnerWaiting);
        this.maxWaitMillis = Math.max(1, maxWaitMillis);
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "swrl-exec-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.pool = new ThreadPoolExecutor(this.workers, this.workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(this.queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> T call(String owner, Callable<T> task, long timeoutSeconds)
            throws TimeoutException, InterruptedException, ExecutionException {
        String key = owner == null || owner.isBlank() ? null : owner;
        AtomicInteger state = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        long id = taskIds.incrementAndGet();
        FutureTask<T> future = new FutureTask<>(() -> {
            if (!state.compareAndSet(0, 1)) {
                return null;
            }
            synchronized (lock) {
                decrement(waiting, key);
                increment(running, key);
                runningSince.put(id, System.nanoTime());
            }
            started.countDown();
            try {
                return task.call();
            } finally {
                synchronized (lock) {
                    decrement(running, key);
                    runningSince.remove(id);
                }
                completed.incrementAndGet();
            }
        });
        synchronized (lock) {
            if (key != null && (value(waiting, key) >= perOwnerWaiting
                    || value(running, key) + value(waiting, key) >= perOwnerActive + perOwnerWaiting)) {
                refusedOwnerLimit.incrementAndGet();
                throw new SwrlBusyException(OWNER_LIMIT, 10,
                        "A rule run of yours is already in progress. Wait for it to finish, then try again.");
            }
            increment(waiting, key);
            try {
                pool.execute(future);
            } catch (RejectedExecutionException e) {
                decrement(waiting, key);
                refusedQueueFull.incrementAndGet();
                throw new SwrlBusyException(QUEUE_FULL, 5, "The rule engine is busy. Try again in a moment.");
            }
        }
        if (!started.await(maxWaitMillis, TimeUnit.MILLISECONDS)) {
            if (state.compareAndSet(0, 2)) {
                pool.remove(future);
                synchronized (lock) {
                    decrement(waiting, key);
                }
                refusedWaitTimeout.incrementAndGet();
                throw new SwrlBusyException(WAIT_TIMEOUT, 5, "The rule engine is busy. Try again in a moment.");
            }
            started.await();
        }
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }

    public Stats stats() {
        long oldest = 0;
        int active;
        synchronized (lock) {
            long now = System.nanoTime();
            for (long since : runningSince.values()) {
                oldest = Math.max(oldest, TimeUnit.NANOSECONDS.toMillis(now - since));
            }
            active = runningSince.size();
        }
        return new Stats(workers, queueCapacity, active, pool.getQueue().size(), refusedQueueFull.get(),
                refusedOwnerLimit.get(), refusedWaitTimeout.get(), completed.get(), oldest);
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    private static int value(Map<String, Integer> map, String key) {
        return key == null ? 0 : map.getOrDefault(key, 0);
    }

    private static void increment(Map<String, Integer> map, String key) {
        if (key != null) {
            map.merge(key, 1, Integer::sum);
        }
    }

    private static void decrement(Map<String, Integer> map, String key) {
        if (key != null) {
            map.computeIfPresent(key, (k, v) -> v <= 1 ? null : v - 1);
        }
    }
}
