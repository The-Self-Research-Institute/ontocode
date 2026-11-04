package self.research.ontology.owlEditor.service.infer;

public interface InferenceAdmission {

    interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    Permit admit(String userId, long estimatedTriples);

    default boolean isBusy() {
        return false;
    }
}
