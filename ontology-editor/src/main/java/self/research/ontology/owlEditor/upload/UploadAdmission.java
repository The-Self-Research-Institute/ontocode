package self.research.ontology.owlEditor.upload;

public interface UploadAdmission {

    Lease tryAdmit() throws InterruptedException;

    int retryAfterSeconds();

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
