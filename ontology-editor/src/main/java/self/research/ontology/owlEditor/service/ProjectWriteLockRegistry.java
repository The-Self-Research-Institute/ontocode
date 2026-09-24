package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class ProjectWriteLockRegistry {

    private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public <T> T runExclusive(String projectId, Callable<T> work) throws Exception {
        ReentrantReadWriteLock.WriteLock lock = lockFor(projectId).writeLock();
        lock.lock();
        try {
            return work.call();
        } finally {
            lock.unlock();
        }
    }

    public <T> T runShared(String projectId, Callable<T> work) throws Exception {
        ReentrantReadWriteLock.ReadLock lock = lockFor(projectId).readLock();
        lock.lock();
        try {
            return work.call();
        } finally {
            lock.unlock();
        }
    }

    private ReentrantReadWriteLock lockFor(String projectId) {
        return locks.computeIfAbsent(projectId, k -> new ReentrantReadWriteLock());
    }
}
