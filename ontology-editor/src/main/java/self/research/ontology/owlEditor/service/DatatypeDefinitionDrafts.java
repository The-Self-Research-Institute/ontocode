package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class DatatypeDefinitionDrafts {

    private static final Comparator<DatatypeDefinitionEntity> NEWEST_FIRST =
            Comparator.comparing(DatatypeDefinitionEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));

    private DatatypeDefinitionDrafts() {
    }

    static List<DatatypeDefinitionEntity> visible(List<DatatypeDefinitionEntity> shared, List<DatatypeDefinitionEntity> mine) {
        Set<String> replaced = mine.stream()
                .map(DatatypeDefinitionEntity::getReplacesId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        List<DatatypeDefinitionEntity> result = new ArrayList<>();
        shared.stream().filter(row -> !replaced.contains(row.getId())).forEach(result::add);
        mine.stream().filter(row -> !row.isDeleted()).forEach(result::add);
        result.sort(NEWEST_FIRST);
        return result;
    }
}
