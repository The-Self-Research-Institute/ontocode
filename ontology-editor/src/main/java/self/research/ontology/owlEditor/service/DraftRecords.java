package self.research.ontology.owlEditor.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

public final class DraftRecords {

    public static final String ONTOLOGY_IRI = "updateOntologyIri";
    public static final String ADD_ANNOTATION = "addOntologyAnnotation";
    public static final String UPDATE_ANNOTATION = "updateOntologyAnnotation";
    public static final String DELETE_ANNOTATION = "deleteOntologyAnnotation";
    public static final String ADD_IMPORT = "addOntologyImport";
    public static final String DELETE_IMPORT = "deleteOntologyImport";
    public static final String ADD_PREFIX = "addPrefix";
    public static final String UPDATE_PREFIX = "updatePrefix";
    public static final String DELETE_PREFIX = "deletePrefix";
    public static final String ADD_GCI = "addGCI";
    public static final String UPDATE_GCI = "updateGCI";
    public static final String DELETE_GCI = "deleteGCI";
    public static final String ADD_AXIOM_ANNOTATION = "addAxiomAnnotation";
    public static final String DELETE_AXIOM_ANNOTATION = "deleteAxiomAnnotation";
    public static final String DELETE_CITATION = "deleteCitation";

    private DraftRecords() {
    }

    public static <T> T describe(DraftTrackingService tracking, boolean draft, String projectId, String userId,
                                 String username, String operationType, Map<String, Object> data, Callable<T> work)
            throws Exception {
        if (!draft || tracking == null || DraftRecordScope.active() || PrefixRollbackActions.historySuppressed()) {
            return work.call();
        }
        DraftRecordScope.Outcome<T> outcome = DraftRecordScope.collect(work);
        if (outcome.changed()) {
            tracking.recordDescribed(projectId, userId, username, operationType, data, outcome.changeSetIds());
        }
        return outcome.value();
    }

    public static void run(DraftTrackingService tracking, boolean draft, String projectId, String userId,
                           String username, String operationType, Map<String, Object> data, Runnable work) {
        try {
            describe(tracking, draft, projectId, userId, username, operationType, data, () -> {
                work.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> ontologyIri(String oldIri, String newIri, String oldVersionIri, String newVersionIri) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("iri", newIri);
        data.put("label", "Ontology IRI");
        data.put("oldValue", oldIri);
        data.put("value", newIri);
        data.put("oldVersionIri", oldVersionIri);
        data.put("versionIri", newVersionIri);
        return data;
    }

    public static Map<String, Object> annotation(String propertyIri, String oldPropertyIri, String oldValue,
                                                 String value, String language, String datatype) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", "Ontology annotation");
        data.put("property", propertyIri);
        if (oldPropertyIri != null && !oldPropertyIri.isBlank() && !oldPropertyIri.equals(propertyIri)) {
            data.put("oldProperty", oldPropertyIri);
        }
        data.put("oldValue", oldValue);
        data.put("value", value);
        data.put("language", language == null || language.isBlank() ? null : language);
        data.put("datatype", datatype == null || datatype.isBlank() ? null : datatype);
        return data;
    }

    public static Map<String, Object> ontologyImport(String importIri) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", "Ontology import");
        data.put("value", importIri);
        return data;
    }

    public static String prefixOperation(String historyOperation) {
        return switch (historyOperation) {
            case "prefixAdded" -> ADD_PREFIX;
            case "prefixDeleted" -> DELETE_PREFIX;
            default -> UPDATE_PREFIX;
        };
    }

    public static Map<String, Object> prefix(String prefix, String oldPrefix, String oldNamespace, String newNamespace) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", prefix + ":");
        data.put("prefix", prefix);
        if (oldPrefix != null && !oldPrefix.isBlank() && !oldPrefix.equals(prefix)) {
            data.put("oldPrefix", oldPrefix);
        }
        data.put("oldValue", oldNamespace);
        data.put("value", newNamespace);
        return data;
    }

    public static Map<String, Object> gci(String oldText, String newText) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", "General class axiom");
        data.put("oldValue", oldText);
        data.put("value", newText);
        return data;
    }

    public static String gciText(String subClass, String superClass) {
        return (subClass == null ? "" : subClass.trim()) + " SubClassOf " + (superClass == null ? "" : superClass.trim());
    }

    public static Map<String, Object> entityAxiom(String entityIri, String axiomType, String expression) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("iri", entityIri);
        data.put("axiomType", axiomType);
        data.put("value", expression);
        return data;
    }

    public static Map<String, Object> axiomAnnotation(String entityIri, String relatedIri, String section,
                                                      String propertyIri, String value, String language) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("iri", entityIri);
        data.put("target", relatedIri);
        data.put("axiomType", section);
        data.put("property", propertyIri);
        data.put("value", value);
        data.put("language", language == null || language.isBlank() ? null : language);
        return data;
    }

    public static Map<String, Object> citation(String citationIri) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("iri", citationIri);
        data.put("label", "Citation");
        data.put("value", citationIri);
        return data;
    }
}
