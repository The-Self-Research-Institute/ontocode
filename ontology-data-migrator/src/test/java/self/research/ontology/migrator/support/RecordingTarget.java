package self.research.ontology.migrator.support;

import self.research.ontology.migrator.core.Checkpoint;
import self.research.ontology.migrator.core.Target;
import self.research.ontology.migrator.core.Write;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RecordingTarget implements Target {

    public final List<Write> writes = new ArrayList<>();
    public final Map<String, Checkpoint> checkpoints = new HashMap<>();
    public int commits;
    public String failWhenKeyEquals;

    @Override
    public Optional<Checkpoint> checkpoint(String name) {
        return Optional.ofNullable(checkpoints.get(name));
    }

    @Override
    public void commit(List<Write> batch, Checkpoint checkpoint) {
        if (failWhenKeyEquals != null) {
            for (Write w : batch) {
                if (w instanceof Write.Upsert u && failWhenKeyEquals.equals(u.keyValues().get(0))) {
                    throw new IllegalStateException("Simulated write error");
                }
            }
        }
        commits++;
        writes.addAll(batch);
        if (checkpoint != null) {
            checkpoints.put(checkpoint.name(), checkpoint);
        }
    }

    public List<Write.Upsert> upserts(String table) {
        return writes.stream().filter(w -> w instanceof Write.Upsert u && u.table().equals(table))
                .map(w -> (Write.Upsert) w).toList();
    }
}
