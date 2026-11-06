package self.research.ontology.migrator.core;

public record Checkpoint(String name, String lastId, long read, long written, long skipped, String status) {

    public static final String RUNNING = "running";
    public static final String DONE = "done";

    public Checkpoint advance(String newLastId, long addRead, long addWritten, long addSkipped, String newStatus) {
        return new Checkpoint(name, newLastId, read + addRead, written + addWritten, skipped + addSkipped, newStatus);
    }
}
