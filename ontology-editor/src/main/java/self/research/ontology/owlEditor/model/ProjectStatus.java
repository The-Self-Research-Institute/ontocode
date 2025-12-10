package self.research.ontology.owlEditor.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.Objects;

/**
 * Lightweight status descriptor persisted to {@code status.json} for each project.
 */
public record ProjectStatus(
        String status,
        String statusMessage,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant updatedAt,
        String filename) {

    public static ProjectStatus uploaded(String filename) {
        return new ProjectStatus("UPLOADED",
                "File uploaded, waiting for processing",
                Instant.now(),
                filename);
    }

    public static ProjectStatus processing(String filename) {
        return new ProjectStatus("PROCESSING",
                "Bulk import in progress",
                Instant.now(),
                filename);
    }

    public static ProjectStatus completed(String filename) {
        return new ProjectStatus("COMPLETED",
                "Ontology imported successfully",
                Instant.now(),
                filename);
    }

    public static ProjectStatus error(String filename, String message) {
        return new ProjectStatus("ERROR",
                Objects.requireNonNullElse(message, "Processing failed"),
                Instant.now(),
                filename);
    }

    public ProjectStatus withFilename(String newFilename) {
        return new ProjectStatus(this.status, this.statusMessage, this.updatedAt, newFilename);
    }
}

