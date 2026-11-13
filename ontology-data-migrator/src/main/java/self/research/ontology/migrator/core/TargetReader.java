package self.research.ontology.migrator.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TargetReader {

    Optional<Map<String, Object>> fetch(String table, List<String> keyColumns, List<Object> keyValues);
}
