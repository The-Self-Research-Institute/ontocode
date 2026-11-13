package self.research.ontology.owlEditor.service;

import java.util.List;

public enum DocumentStore {

    DATATYPE_DEFINITION("datatypeDefinition"),
    FUZZY_RULE("fuzzyRule"),
    SWRL_RULE("swrlRule", "executionCount", "lastExecutionTime", "lastExecutedAt");

    private final String key;
    private final List<String> volatileFields;

    DocumentStore(String key, String... volatileFields) {
        this.key = key;
        this.volatileFields = List.of(volatileFields);
    }

    public List<String> volatileFields() {
        return volatileFields;
    }

    public String key() {
        return key;
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
