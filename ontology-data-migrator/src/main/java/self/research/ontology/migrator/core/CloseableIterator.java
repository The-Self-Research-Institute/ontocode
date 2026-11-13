package self.research.ontology.migrator.core;

import java.util.Iterator;
import java.util.List;

public interface CloseableIterator<T> extends Iterator<T>, AutoCloseable {

    @Override
    void close();

    static <T> CloseableIterator<T> of(List<T> items) {
        Iterator<T> it = items.iterator();
        return new CloseableIterator<>() {
            @Override
            public void close() {
            }

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public T next() {
                return it.next();
            }
        };
    }
}
