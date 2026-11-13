package self.research.ontology.common.db.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public final class SqlLists {

    private SqlLists() {
    }

    public static String placeholders(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("A list needs at least one value");
        }
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    public static <T> void inChunks(List<T> items, int chunkSize, Consumer<List<T>> action) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive");
        }
        for (int from = 0; from < items.size(); from += chunkSize) {
            action.accept(new ArrayList<>(items.subList(from, Math.min(from + chunkSize, items.size()))));
        }
    }
}
