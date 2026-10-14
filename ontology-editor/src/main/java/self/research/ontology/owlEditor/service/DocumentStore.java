package self.research.ontology.owlEditor.service;

import java.util.List;

public enum DocumentStore {

    DATATYPE_DEFINITION("datatypeDefinition", "datatype_definitions"),
    FUZZY_RULE("fuzzyRule", "fuzzy_rules"),
    SWRL_RULE("swrlRule", "swrl_rules", "executionCount", "lastExecutionTime", "lastExecutedAt");

    private final String key;
    private final String collection;
    private final List<String> volatileFields;

    DocumentStore(String key, String collection, String... volatileFields) {
        this.key = key;
        this.collection = collection;
        this.volatileFields = List.of(volatileFields);
    }

    public List<String> volatileFields() {
        return volatileFields;
    }

    public String key() {
        return key;
    }

    public String collection() {
        return collection;
    }

    public static DocumentStore find(String key) {
        for (DocumentStore store : values()) {
            if (store.key.equals(key)) {
                return store;
            }
        }
        return null;
    }
}
