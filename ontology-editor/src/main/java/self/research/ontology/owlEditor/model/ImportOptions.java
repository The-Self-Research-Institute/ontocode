package self.research.ontology.owlEditor.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImportOptions {
    private ImportMode mode;
    private PartitionStrategy partitionStrategy;

    public static ImportOptions defaults() {
        return ImportOptions.builder()
                .mode(ImportMode.FULL)
                .partitionStrategy(PartitionStrategy.NONE)
                .build();
    }

    public enum ImportMode {
        FULL,
        INCREMENTAL,
        DIFF
    }

    public enum PartitionStrategy {
        NONE,
        NAMESPACE
    }
}
