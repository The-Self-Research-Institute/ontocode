package self.research.ontology.owlEditor.service.infer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InferenceCapacity {

    @Value("${ontocode.capacity.inference.max-asserted-triples:200000}")
    private int maxAssertedTriples = InferenceProfile.MAX_ASSERTED_TRIPLES;

    @Value("${ontocode.capacity.inference.max-equality-triples:100000}")
    private int maxEqualityTriples = InferenceProfile.MAX_EQUALITY_TRIPLES;

    @Value("${ontocode.capacity.inference.max-group-size:10}")
    private int maxGroupSize = InferenceProfile.MAX_GROUP_SIZE;

    @Value("${ontocode.capacity.inference.cache-snapshots:2}")
    private int cacheSnapshots = InferenceProfile.CACHE_MAX_SNAPSHOTS;

    @Value("${ontocode.capacity.inference.cache-triples:600000}")
    private long cacheTriples = InferenceProfile.CACHE_MAX_TRIPLES;

    @Value("${ontocode.capacity.inference.busy-wait-ms:5000}")
    private long busyWaitMs = InferenceProfile.BUSY_WAIT_MS;

    public int maxAssertedTriples() {
        return maxAssertedTriples;
    }

    public int maxEqualityTriples() {
        return maxEqualityTriples;
    }

    public int maxGroupSize() {
        return maxGroupSize;
    }

    public int cacheSnapshots() {
        return cacheSnapshots;
    }

    public long cacheTriples() {
        return cacheTriples;
    }

    public long busyWaitMs() {
        return busyWaitMs;
    }
}
