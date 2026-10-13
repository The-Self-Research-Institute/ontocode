package self.research.ontology.owlEditor.service.spreadsheet.rule;

import java.util.EnumSet;
import java.util.Set;

import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.ANNOTATIONS;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.CHARACTERISTICS;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.DIFFERENT_FROM;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.DISJOINT_WITH;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.DOMAIN;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.EQUIVALENT_TO;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.FACTS;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.INVERSE_OF;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.RANGE;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.SAME_AS;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.SUB_CLASS_OF;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.SUB_PROPERTY_OF;
import static self.research.ontology.owlEditor.service.spreadsheet.rule.ClauseKind.TYPES;

public enum EntityKind {
    CLASS("Class", EnumSet.of(SUB_CLASS_OF, EQUIVALENT_TO, DISJOINT_WITH, ANNOTATIONS), Set.of()),
    INDIVIDUAL("Individual", EnumSet.of(TYPES, FACTS, SAME_AS, DIFFERENT_FROM, ANNOTATIONS), Set.of()),
    OBJECT_PROPERTY("ObjectProperty",
        EnumSet.of(DOMAIN, RANGE, SUB_PROPERTY_OF, INVERSE_OF, CHARACTERISTICS, ANNOTATIONS),
        Set.of("Functional", "InverseFunctional", "Transitive", "Symmetric", "Asymmetric", "Reflexive", "Irreflexive")),
    DATA_PROPERTY("DataProperty",
        EnumSet.of(DOMAIN, RANGE, SUB_PROPERTY_OF, CHARACTERISTICS, ANNOTATIONS),
        Set.of("Functional")),
    ANNOTATION_PROPERTY("AnnotationProperty", EnumSet.of(DOMAIN, RANGE, SUB_PROPERTY_OF, ANNOTATIONS), Set.of()),
    DATATYPE("Datatype", EnumSet.of(ANNOTATIONS), Set.of());

    private final String keyword;
    private final Set<ClauseKind> allowedClauses;
    private final Set<String> allowedCharacteristics;

    EntityKind(String keyword, Set<ClauseKind> allowedClauses, Set<String> allowedCharacteristics) {
        this.keyword = keyword;
        this.allowedClauses = allowedClauses;
        this.allowedCharacteristics = allowedCharacteristics;
    }

    public String keyword() {
        return keyword;
    }

    public boolean allows(ClauseKind clause) {
        return allowedClauses.contains(clause);
    }

    public Set<String> allowedCharacteristics() {
        return allowedCharacteristics;
    }

    public static EntityKind fromKeyword(String keyword) {
        for (EntityKind kind : values()) {
            if (kind.keyword.equals(keyword)) {
                return kind;
            }
        }
        return null;
    }
}
