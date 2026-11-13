package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;
import self.research.ontology.owlEditor.model.FuzzyRuleEntity;
import self.research.ontology.owlEditor.repository.DatatypeDefinitionRepository;
import self.research.ontology.owlEditor.repository.FuzzyRuleRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
@Component
public class DocumentRollbackActions implements RollbackActionRunner {

    public static final String SAVED_RULES_MOVED = "Saved rules moved into the ontology; undo from the rule list";

    public static final String LEGACY_FORMAT = "This change was recorded before the move to PostgreSQL and its saved copy cannot be restored";

    public static boolean isRetired(RollbackAction action) {
        return action != null && DocumentStore.SWRL_RULE.key().equals(action.store());
    }

    private record Access(Class<?> type, Function<String, Object> find, Consumer<Object> save, Consumer<String> delete) {
    }

    private final Map<DocumentStore, Access> stores = new EnumMap<>(DocumentStore.class);

    public DocumentRollbackActions(DatatypeDefinitionRepository datatypes, FuzzyRuleRepository fuzzyRules) {
        stores.put(DocumentStore.DATATYPE_DEFINITION, new Access(DatatypeDefinitionEntity.class,
                id -> datatypes.findById(id).orElse(null), entity -> datatypes.save((DatatypeDefinitionEntity) entity),
                datatypes::deleteById));
        stores.put(DocumentStore.FUZZY_RULE, new Access(FuzzyRuleEntity.class,
                id -> fuzzyRules.findById(id).orElse(null), entity -> fuzzyRules.save((FuzzyRuleEntity) entity),
                fuzzyRules::deleteById));
    }

    @Override
    public void run(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        if (isRetired(action)) {
            throw new IllegalStateException(SAVED_RULES_MOVED);
        }
        DocumentStore store = requireStore(action);
        Access access = stores.get(store);
        String id = identity(action);
        if (action.document() == null) {
            access.delete().accept(id);
            return;
        }
        JsonNode target = DocumentSnapshots.tree(action.document());
        requirePlainJson(target);
        if (!projectId.equals(target.path("projectId").asText(null))) {
            throw new IllegalArgumentException("The stored document does not belong to this project");
        }
        try {
            access.save().accept(DocumentSnapshots.MAPPER.treeToValue(target, access.type()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean holds(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        if (isRetired(action)) {
            return false;
        }
        DocumentStore store = DocumentStore.find(action.store());
        Access access = store == null ? null : stores.get(store);
        if (access == null) {
            return false;
        }
        String id;
        try {
            id = identity(action);
        } catch (IllegalStateException legacy) {
            return false;
        }
        Object current = access.find().apply(id);
        if (action.expectedDocument() == null) {
            return current == null;
        }
        if (current == null) {
            return false;
        }
        JsonNode expected = DocumentSnapshots.tree(action.expectedDocument());
        if (isLegacy(expected)) {
            return false;
        }
        JsonNode actual = DocumentSnapshots.MAPPER.valueToTree(current);
        for (String field : store.volatileFields()) {
            ((ObjectNode) actual).remove(field);
            ((ObjectNode) expected).remove(field);
        }
        return actual.equals(expected);
    }

    private static DocumentStore requireStore(RollbackAction action) {
        DocumentStore store = DocumentStore.find(action.store());
        if (store == null) {
            throw new IllegalArgumentException("Unknown document store " + action.store());
        }
        return store;
    }

    private static String identity(RollbackAction action) {
        String json = action.document() != null ? action.document() : action.expectedDocument();
        if (json == null) {
            throw new IllegalArgumentException("A document action needs a before or after state");
        }
        JsonNode tree = DocumentSnapshots.tree(json);
        requirePlainJson(tree);
        JsonNode id = tree.get("id");
        if (id == null || id.isNull()) {
            throw new IllegalArgumentException("A document action needs a document id");
        }
        return id.asText();
    }

    private static boolean isLegacy(JsonNode tree) {
        return tree.has("_id");
    }

    private static void requirePlainJson(JsonNode tree) {
        if (isLegacy(tree)) {
            throw new IllegalStateException(LEGACY_FORMAT);
        }
    }
}
