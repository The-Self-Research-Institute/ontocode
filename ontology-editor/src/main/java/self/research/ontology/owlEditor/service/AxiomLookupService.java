package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.semanticweb.owlapi.model.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Locates asserted OWL axioms for an entity row (Protégé-style frame sections).
 */
@Service
@Slf4j
public class AxiomLookupService {

    public OWLAxiom findFirst(OWLOntology ontology, String entityIri, String relatedIri, String sectionName) {
        List<OWLAxiom> matches = findAll(ontology, entityIri, relatedIri, sectionName);
        return matches.isEmpty() ? null : matches.get(0);
    }

    public List<OWLAxiom> findAll(OWLOntology ontology, String entityIri, String relatedIri, String sectionName) {
        List<OWLAxiom> results = new ArrayList<>();
        if (entityIri == null || relatedIri == null || entityIri.isBlank() || relatedIri.isBlank()) {
            return results;
        }

        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        IRI eIri = IRI.create(entityIri.trim());
        String related = stripDisplayMetadata(relatedIri.trim());
        String sec = sectionName != null ? sectionName.toLowerCase() : "";

        try {
            if (sec.contains("range")) {
                collectRangeAxioms(ontology, df, eIri, related, results);
            } else if (sec.contains("domain")) {
                collectDomainAxioms(ontology, df, eIri, related, results);
            } else if (sec.contains("subclass") || sec.contains("sub class")) {
                ontology.getSubClassAxiomsForSubClass(df.getOWLClass(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, related))
                        .forEach(results::add);
            } else if (sec.contains("equivalent")) {
                ontology.getEquivalentClassesAxioms(df.getOWLClass(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, related))
                        .forEach(results::add);
                ontology.getEquivalentObjectPropertiesAxioms(df.getOWLObjectProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, related))
                        .forEach(results::add);
                ontology.getEquivalentDataPropertiesAxioms(df.getOWLDataProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, related))
                        .forEach(results::add);
            } else if (sec.contains("disjoint")) {
                ontology.getDisjointClassesAxioms(df.getOWLClass(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, related))
                        .forEach(results::add);
                ontology.getDisjointObjectPropertiesAxioms(df.getOWLObjectProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, related))
                        .forEach(results::add);
            } else if (sec.contains("inverse")) {
                ontology.getInverseObjectPropertyAxioms(df.getOWLObjectProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, related))
                        .forEach(results::add);
            } else if (sec.contains("subproperty") || sec.contains("superprop") || sec.contains("super property")) {
                ontology.getObjectSubPropertyAxiomsForSubProperty(df.getOWLObjectProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, related))
                        .forEach(results::add);
                ontology.getDataSubPropertyAxiomsForSubProperty(df.getOWLDataProperty(eIri)).stream()
                        .filter(ax -> axiomInvolves(ax, related))
                        .forEach(results::add);
            } else {
                IRI rIri = IRI.create(related);
                ontology.getAxioms().stream()
                        .filter(ax -> ax.getSignature().stream().anyMatch(e -> e.getIRI().equals(eIri))
                                && ax.getSignature().stream().anyMatch(e -> e.getIRI().equals(rIri)))
                        .forEach(results::add);
            }
        } catch (Exception e) {
            log.warn("findAll axioms error entity={} related={}: {}", entityIri, relatedIri, e.getMessage());
        }
        return results;
    }

    private void collectRangeAxioms(OWLOntology ontology, OWLDataFactory df, IRI eIri, String related,
                                    List<OWLAxiom> results) {
        try {
            ontology.getDataPropertyRangeAxioms(df.getOWLDataProperty(eIri)).stream()
                    .filter(ax -> axiomInvolves(ax, related))
                    .forEach(results::add);
        } catch (Exception ignored) {
            // not a data property
        }
        try {
            ontology.getObjectPropertyRangeAxioms(df.getOWLObjectProperty(eIri)).stream()
                    .filter(ax -> axiomInvolves(ax, related))
                    .forEach(results::add);
        } catch (Exception ignored) {
            // not an object property
        }
    }

    private void collectDomainAxioms(OWLOntology ontology, OWLDataFactory df, IRI eIri, String related,
                                     List<OWLAxiom> results) {
        try {
            ontology.getDataPropertyDomainAxioms(df.getOWLDataProperty(eIri)).stream()
                    .filter(ax -> axiomInvolves(ax, related))
                    .forEach(results::add);
        } catch (Exception ignored) {
            // not a data property
        }
        try {
            ontology.getObjectPropertyDomainAxioms(df.getOWLObjectProperty(eIri)).stream()
                    .filter(ax -> axiomInvolves(ax, related))
                    .forEach(results::add);
        } catch (Exception ignored) {
            // not an object property
        }
    }

    private boolean axiomInvolves(OWLAxiom axiom, String iri) {
        if (iri == null || iri.isBlank()) return false;
        String target = stripDisplayMetadata(iri);
        if (axiom.toString().contains(target)) {
            return true;
        }
        return axiom.getSignature().stream().anyMatch(e -> e.getIRI().toString().equals(target));
    }

    private String stripDisplayMetadata(String value) {
        if (value.contains("|||")) {
            return value.split("\\|\\|\\|")[0];
        }
        return value;
    }
}
