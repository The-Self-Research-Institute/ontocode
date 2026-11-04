package self.research.ontology.owlEditor.service.infer;

public interface InferenceCacheAccounting {

    void add(long bytes);

    void remove(long bytes);

    InferenceCacheAccounting NONE = new InferenceCacheAccounting() {
        @Override
        public void add(long bytes) {
        }

        @Override
        public void remove(long bytes) {
        }
    };
}
