package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProjectWriteLockRegistry {

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public <T> T runExclusive(String projectId, Callable<T> work) throws Exception {
        Object lock = locks.computeIfAbsent(projectId, k -> new Object());
        synchronized (lock) {
            return work.call();
        }
    }
}
