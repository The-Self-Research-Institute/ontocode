package self.research.ontology.reasoner.service;

import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.function.Consumer;

@Slf4j
class ReasoningJobEventSender {

    private final Consumer<ReasoningJobNotifier.JobSnapshot> publisher;
    private final int capacity;
    private final LinkedList<ReasoningJobNotifier.JobSnapshot> pending = new LinkedList<>();
    private final Thread sender;
    private boolean stopping;

    ReasoningJobEventSender(Consumer<ReasoningJobNotifier.JobSnapshot> publisher, int capacity) {
        this.publisher = publisher;
        this.capacity = Math.max(1, capacity);
        this.sender = new Thread(this::run, "reasoning-job-notifier");
        this.sender.setDaemon(true);
        this.sender.start();
    }

    synchronized void submit(ReasoningJobNotifier.JobSnapshot snapshot) {
        if (stopping || snapshot == null) {
            return;
        }
        boolean terminal = isTerminal(snapshot);
        if (!terminal && mergeIntoPending(snapshot)) {
            return;
        }
        if (pending.size() >= capacity && !dropOldestNonTerminal() && !terminal) {
            log.warn("[Reasoning] Event queue full — dropping {} update for job {}", snapshot.status(), snapshot.jobId());
            return;
        }
        pending.addLast(snapshot);
        notifyAll();
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    void shutdown(long drainMs) {
        synchronized (this) {
            stopping = true;
            notifyAll();
        }
        try {
            sender.join(Math.max(0, drainMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (sender.isAlive()) {
            sender.interrupt();
            log.info("[Reasoning] Stopped job event sender with {} event(s) still unsent", pendingCount());
        }
    }

    private boolean mergeIntoPending(ReasoningJobNotifier.JobSnapshot snapshot) {
        ListIterator<ReasoningJobNotifier.JobSnapshot> it = pending.listIterator();
        while (it.hasNext()) {
            ReasoningJobNotifier.JobSnapshot queued = it.next();
            if (!queued.jobId().equals(snapshot.jobId())) {
                continue;
            }
            if (!isTerminal(queued)) {
                it.set(snapshot);
            }
            return true;
        }
        return false;
    }

    private boolean dropOldestNonTerminal() {
        Iterator<ReasoningJobNotifier.JobSnapshot> it = pending.iterator();
        while (it.hasNext()) {
            if (!isTerminal(it.next())) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            ReasoningJobNotifier.JobSnapshot next;
            synchronized (this) {
                while (pending.isEmpty() && !stopping) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (pending.isEmpty()) {
                    return;
                }
                next = pending.pollFirst();
            }
            try {
                publisher.accept(next);
            } catch (Exception e) {
                log.warn("[Reasoning] Could not publish event for job {}: {}", next.jobId(), e.getMessage());
            }
        }
    }

    static boolean isTerminal(ReasoningJobNotifier.JobSnapshot snapshot) {
        return "COMPLETED".equals(snapshot.status()) || "FAILED".equals(snapshot.status());
    }
}
