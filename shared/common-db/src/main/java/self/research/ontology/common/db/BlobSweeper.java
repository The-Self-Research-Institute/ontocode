package self.research.ontology.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

public class BlobSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(BlobSweeper.class);

    private final DiskBlobStore disk;
    private final Duration minAge;

    public BlobSweeper(DiskBlobStore disk, Duration minAge) {
        this.disk = disk;
        this.minAge = minAge;
    }

    @Scheduled(fixedDelayString = "${ontocode.blobs.sweep.interval-ms:3600000}",
            initialDelayString = "${ontocode.blobs.sweep.initial-delay-ms:600000}")
    public void sweep() {
        try {
            disk.sweepOrphans(minAge);
        } catch (RuntimeException e) {
            LOG.warn("[Blobs] Orphan file sweep failed: {}", e.getMessage());
        }
    }
}
