package self.research.ontology.common.disk;

public class InsufficientDiskSpaceException extends RuntimeException {

    public static final String MESSAGE = "The server disk is nearly full. Free some space or try again later.";

    private final long neededBytes;
    private final long usableBytes;

    public InsufficientDiskSpaceException(long neededBytes, long usableBytes) {
        super(MESSAGE);
        this.neededBytes = neededBytes;
        this.usableBytes = usableBytes;
    }

    public long neededBytes() {
        return neededBytes;
    }

    public long usableBytes() {
        return usableBytes;
    }
}
