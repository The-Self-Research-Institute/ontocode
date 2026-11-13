package self.research.ontology.migrator.core;

import java.util.List;
import java.util.Optional;

public interface Target {

    Optional<Checkpoint> checkpoint(String name);

    void commit(List<Write> writes, Checkpoint checkpointOrNull);
}
