package self.research.ontology.owlEditor.upload;

import java.io.IOException;

public class UploadLimitExceededException extends IOException {

    private final long limitBytes;

    public UploadLimitExceededException(String message, long limitBytes) {
        super(message);
        this.limitBytes = limitBytes;
    }

    public long getLimitBytes() {
        return limitBytes;
    }
}
