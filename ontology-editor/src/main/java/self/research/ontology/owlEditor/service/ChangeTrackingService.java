package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.OntologyChange;
import self.research.ontology.owlEditor.model.OntologyChange.ChangeType;
import self.research.ontology.owlEditor.repository.OntologyChangeRepository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChangeTrackingService {

    private static final Logger log = LoggerFactory.getLogger(ChangeTrackingService.class);

    @Autowired
    private OntologyChangeRepository changeRepository;

    public OntologyChange recordChange(OntologyChange change) {
        log.info("[SAVE] Recording change - type: {}, entityIRI: {}, oldValue: '{}', newValue: '{}'",
            change.getChangeType(),

            change.getOldValue(),
            change.getNewValue());
        OntologyChange saved = changeRepository.save(change);
        log.info("[SAVE COMPLETE] Saved change ID: {}, oldValue: '{}', newValue: '{}'",
            saved.getId(),
            saved.getOldValue(),
            saved.getNewValue());
        log.info("Recorded change: {} by {} for project {}",
            change.getChangeType(),
            change.getUsername(),
            change.getProjectId());
        return saved;
    }

    public OntologyChange recordAddClass(String projectId, String userId, String username,
                                        OWLClass owlClass, OWLOntology ontology, String sessionId) {
        String label = getLabel(owlClass, ontology);

        OntologyChange change = new OntologyChange.Builder(projectId, userId, username, ChangeType.ADD_CLASS)
            .changeCategory("CLASS")
            .entityIRI(owlClass.getIRI().toString())
            .entityLabel(label)
            .description("Added class: " + label)
            .sessionId(sessionId)
            .build();

        return recordChange(change);
    }

    public OntologyChange recordRemoveClass(String projectId, String userId, String username,
                                           OWLClass owlClass, OWLOntology ontology, String sessionId) {
        String label = getLabel(owlClass, ontology);

        OntologyChange change = new OntologyChange.Builder(projectId, userId, username, ChangeType.REMOVE_CLASS)
            .changeCategory("CLASS")
            .entityIRI(owlClass.getIRI().toString())
            .entityLabel(label)
            .description("Removed class: " + label)
            .sessionId(sessionId)
            .build();

        return recordChange(change);
    }

    public OntologyChange recordAddAxiom(String projectId, String userId, String username,
                                        OWLAxiom axiom, OWLOntology ontology, String sessionId) {
        OntologyChange change = new OntologyChange.Builder(projectId, userId, username, ChangeType.ADD_AXIOM)
            .changeCategory(determineCategory(axiom))
            .axiomAfter(axiom.toString())
            .description("Added axiom: " + formatAxiom(axiom, ontology))
            .sessionId(sessionId)
            .build();

        return recordChange(change);
    }

    public OntologyChange recordRemoveAxiom(String projectId, String userId, String username,
                                           OWLAxiom axiom, OWLOntology ontology, String sessionId) {
        OntologyChange change = new OntologyChange.Builder(projectId, userId, username, ChangeType.REMOVE_AXIOM)
            .changeCategory(determineCategory(axiom))
            .axiomBefore(axiom.toString())
            .description("Removed axiom: " + formatAxiom(axiom, ontology))
            .sessionId(sessionId)
            .build();

        return recordChange(change);
    }

    public OntologyChange recordAnnotationChange(String projectId, String userId, String username,
                                                 IRI entityIRI, String oldValue, String newValue,
                                                 String annotationType, String sessionId) {
        OntologyChange change = new OntologyChange.Builder(projectId, userId, username, ChangeType.MODIFY_ANNOTATION)
            .changeCategory("ANNOTATION")
            .entityIRI(entityIRI.toString())
            .oldValue(oldValue)
            .newValue(newValue)
            .description("Modified " + annotationType + " annotation")
            .sessionId(sessionId)
            .metadata("annotationType", annotationType)
            .build();

        return recordChange(change);
    }

    public List<OntologyChange> getProjectHistory(String projectId, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return changeRepository.findByProjectIdOrderByTimestampDesc(projectId, pageable);
    }

    public List<OntologyChange> getEntityHistory(String projectId, String entityIRI) {
        return changeRepository.findByProjectIdAndEntityIRIOrderByTimestampDesc(projectId, entityIRI);
    }

    public List<OntologyChange> getUserChanges(String projectId, String userId) {
        List<OntologyChange> allUserChanges = changeRepository.findByUserIdOrderByTimestampDesc(userId);
        return allUserChanges.stream()
            .filter(change -> change.getProjectId().equals(projectId))
            .collect(Collectors.toList());
    }

    public List<OntologyChange> getChangesInRange(String projectId, LocalDateTime start, LocalDateTime end) {
        return changeRepository.findByProjectIdAndTimestampBetweenOrderByTimestampDesc(projectId, start, end);
    }

    public List<OntologyChange> getRecentChanges(String projectId, int count) {
        Pageable pageable = PageRequest.of(0, count);
        return changeRepository.findRecentChanges(projectId, pageable);
    }

    public List<OntologyChange> getSessionChanges(String sessionId) {
        return changeRepository.findBySessionIdOrderByTimestampDesc(sessionId);
    }

    public List<OntologyChange> getChangesByType(String projectId, ChangeType changeType) {
        return changeRepository.findByProjectIdAndChangeTypeOrderByTimestampDesc(projectId, changeType);
    }

    public List<OntologyChange> getChangesByCategory(String projectId, String category) {
        return changeRepository.findByProjectIdAndChangeCategoryOrderByTimestampDesc(projectId, category);
    }

    public Map<String, Object> getChangeStatistics(String projectId) {
        List<OntologyChange> allChanges = changeRepository.findByProjectIdOrderByTimestampDesc(projectId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalChanges", allChanges.size());
        stats.put("revertedChanges", allChanges.stream().filter(OntologyChange::isReverted).count());

        Map<ChangeType, Long> byType = allChanges.stream()
            .collect(Collectors.groupingBy(OntologyChange::getChangeType, Collectors.counting()));
        stats.put("changesByType", byType);

        Map<String, Long> byCategory = allChanges.stream()
            .filter(c -> c.getChangeCategory() != null)
            .collect(Collectors.groupingBy(OntologyChange::getChangeCategory, Collectors.counting()));
        stats.put("changesByCategory", byCategory);

        Map<String, Long> byUser = allChanges.stream()
            .filter(c -> c.getUsername() != null)
            .collect(Collectors.groupingBy(OntologyChange::getUsername, Collectors.counting()));
        stats.put("changesByUser", byUser);

        Map<String, Long> byDay = allChanges.stream()
            .collect(Collectors.groupingBy(
                c -> c.getTimestamp().toLocalDate().toString(),
                Collectors.counting()
            ));
        stats.put("changesByDay", byDay);

        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        long recentChanges = allChanges.stream()
            .filter(c -> c.getTimestamp().isAfter(yesterday))
            .count();
        stats.put("changesLast24Hours", recentChanges);

        return stats;
    }

    public boolean revertChange(String changeId, String userId, String username) {
        Optional<OntologyChange> changeOpt = changeRepository.findById(changeId);
        if (changeOpt.isEmpty()) {
            log.warn("Change not found: {}", changeId);
            return false;
        }

        OntologyChange change = changeOpt.get();
        if (change.isReverted()) {
            log.warn("Change already reverted: {}", changeId);
            return false;
        }

        change.setReverted(true);
        change.setRevertedBy(username);
        change.setRevertedAt(LocalDateTime.now());
        changeRepository.save(change);

        OntologyChange revertChange = createRevertChange(change, userId, username);
        recordChange(revertChange);

        log.info("Reverted change {} by {}", changeId, username);
        return true;
    }

    private OntologyChange createRevertChange(OntologyChange original, String userId, String username) {
        ChangeType revertType = getRevertType(original.getChangeType());

        OntologyChange revert = new OntologyChange.Builder(
            original.getProjectId(),
            userId,
            username,
            revertType
        )
            .changeCategory(original.getChangeCategory())
            .entityIRI(original.getEntityIRI())
            .entityLabel(original.getEntityLabel())
            .oldValue(original.getNewValue())
            .newValue(original.getOldValue())
            .axiomBefore(original.getAxiomAfter())
            .axiomAfter(original.getAxiomBefore())
            .description("Reverted: " + original.getDescription())
            .metadata("revertedChangeId", original.getId())
            .build();

        return revert;
    }

    private ChangeType getRevertType(ChangeType original) {
        switch (original) {
            case ADD_CLASS: return ChangeType.REMOVE_CLASS;
            case REMOVE_CLASS: return ChangeType.ADD_CLASS;
            case ADD_OBJECT_PROPERTY: return ChangeType.REMOVE_OBJECT_PROPERTY;
            case REMOVE_OBJECT_PROPERTY: return ChangeType.ADD_OBJECT_PROPERTY;
            case ADD_DATA_PROPERTY: return ChangeType.REMOVE_DATA_PROPERTY;
            case REMOVE_DATA_PROPERTY: return ChangeType.ADD_DATA_PROPERTY;
            case ADD_INDIVIDUAL: return ChangeType.REMOVE_INDIVIDUAL;
            case REMOVE_INDIVIDUAL: return ChangeType.ADD_INDIVIDUAL;
            case ADD_AXIOM: return ChangeType.REMOVE_AXIOM;
            case REMOVE_AXIOM: return ChangeType.ADD_AXIOM;
            default: return ChangeType.OTHER;
        }
    }

    public List<OntologyChange> getChangesBetweenVersions(String projectId,
                                                          LocalDateTime version1,
                                                          LocalDateTime version2) {
        return changeRepository.findByProjectIdAndTimestampBetweenOrderByTimestampDesc(
            projectId,
            version1,
            version2
        );
    }

    public void clearProjectHistory(String projectId) {
        changeRepository.deleteByProjectId(projectId);
        log.info("Cleared change history for project: {}", projectId);
    }

    public List<Map<String, Object>> exportChangeHistory(String projectId) {
        List<OntologyChange> changes = changeRepository.findByProjectIdOrderByTimestampDesc(projectId);

        return changes.stream()
            .map(this::changeToMap)
            .collect(Collectors.toList());
    }

    private String getLabel(OWLEntity entity, OWLOntology ontology) {
        return ontology.getAnnotationAssertionAxioms(entity.getIRI()).stream()
            .filter(a -> a.getProperty().isLabel())
            .findFirst()
            .map(a -> a.getValue().asLiteral().map(OWLLiteral::getLiteral).orElse(""))
            .orElse(getLocalName(entity.getIRI().toString()));
    }

    private String getLocalName(String iri) {
        int hashIndex = iri.lastIndexOf('#');
        int slashIndex = iri.lastIndexOf('/');
        int splitIndex = Math.max(hashIndex, slashIndex);
        return splitIndex >= 0 && splitIndex < iri.length() - 1
            ? iri.substring(splitIndex + 1)
            : iri;
    }

    private String formatAxiom(OWLAxiom axiom, OWLOntology ontology) {
        String axiomString = axiom.toString();

        for (OWLEntity entity : axiom.getSignature()) {
            String label = getLabel(entity, ontology);
            if (!label.isEmpty()) {
                axiomString = axiomString.replace(entity.getIRI().toString(), label);
            }
        }
        return axiomString;
    }

    private String determineCategory(OWLAxiom axiom) {
        if (axiom instanceof OWLClassAxiom) return "CLASS";
        if (axiom instanceof OWLObjectPropertyAxiom) return "OBJECT_PROPERTY";
        if (axiom instanceof OWLDataPropertyAxiom) return "DATA_PROPERTY";
        if (axiom instanceof OWLIndividualAxiom) return "INDIVIDUAL";
        if (axiom instanceof OWLAnnotationAxiom) return "ANNOTATION";
        return "AXIOM";
    }

    private Map<String, Object> changeToMap(OntologyChange change) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", change.getId());
        map.put("timestamp", change.getTimestamp().toString());
        map.put("username", change.getUsername());
        map.put("changeType", change.getChangeType().toString());
        map.put("category", change.getChangeCategory());
        map.put("entityIRI", change.getEntityIRI());
        map.put("entityLabel", change.getEntityLabel());
        map.put("description", change.getDescription());
        map.put("reverted", change.isReverted());
        return map;
    }
}