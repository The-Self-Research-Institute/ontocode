package self.research.ontology.owlEditor.service;

public class MergeInputTooLargeException extends RuntimeException {

    private final long limitBytes;

    public MergeInputTooLargeException(String what, long limitBytes) {
        super(what + " is larger than the " + (limitBytes / (1024L * 1024L))
                + " MB this server can merge at once. Split it into smaller files and merge them one at a time.");
        this.limitBytes = limitBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }
}
