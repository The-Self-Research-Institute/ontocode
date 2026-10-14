package self.research.ontology.owlEditor.service.spreadsheet;

import self.research.ontology.owlEditor.service.spreadsheet.rule.EntityKind;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface OntologyLookup {

    String defaultNamespace();

    Map<String, String> prefixes();

    Optional<KnownEntity> byIri(String iri);

    List<KnownEntity> byLabel(String label);

    default List<KnownEntity> byShortName(String name) {
        return List.of();
    }

    record KnownEntity(String iri, Set<EntityKind> kinds, String dataRange) {
    }
}
