package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.util.SWRLVariableExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for SWRL (Semantic Web Rule Language) operations.
 * Supports creating, parsing, validating, and executing SWRL rules.
 */
@Service
public class SWRLService {

    private static final Logger log = LoggerFactory.getLogger(SWRLService.class);

    /**
     * Get all SWRL rules from an ontology
     */
    public Set<SWRLRule> getAllRules(OWLOntology ontology) {
        return ontology.getAxioms(AxiomType.SWRL_RULE, Imports.INCLUDED);
    }

    /**
     * Add a SWRL rule to the ontology
     */
    public void addRule(OWLOntology ontology, SWRLRule rule) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        manager.addAxiom(ontology, rule);
        log.info("Added SWRL rule to ontology");
    }

    /**
     * Remove a SWRL rule from the ontology
     */
    public void removeRule(OWLOntology ontology, SWRLRule rule) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        manager.removeAxiom(ontology, rule);
        log.info("Removed SWRL rule from ontology");
    }

    /**
     * Create a SWRL rule from body and head atoms
     */
    public SWRLRule createRule(OWLOntology ontology, Set<SWRLAtom> body, Set<SWRLAtom> head) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        return df.getSWRLRule(body, head);
    }

    /**
     * Parse SWRL rule from Manchester syntax-like string
     * Example: "Person(?p) ^ hasAge(?p, ?age) ^ greaterThan(?age, 18) -> Adult(?p)"
     */
    public SWRLRule parseRule(OWLOntology ontology, String ruleString) {
        try {
            // Split into body and head
            String[] parts = ruleString.split("->");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid SWRL rule format. Expected: body -> head");
            }

            String bodyString = parts[0].trim();
            String headString = parts[1].trim();

            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            
            Set<SWRLAtom> bodyAtoms = parseAtoms(ontology, bodyString);
            Set<SWRLAtom> headAtoms = parseAtoms(ontology, headString);

            return df.getSWRLRule(bodyAtoms, headAtoms);
            
        } catch (Exception e) {
            log.error("Error parsing SWRL rule: {}", ruleString, e);
            throw new RuntimeException("Failed to parse SWRL rule: " + e.getMessage(), e);
        }
    }

    /**
     * Parse atoms from string (simplified parser)
     */
    private Set<SWRLAtom> parseAtoms(OWLOntology ontology, String atomsString) {
        Set<SWRLAtom> atoms = new HashSet<>();
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        
        // Split by ^
        String[] atomStrings = atomsString.split("\\^");
        
        for (String atomString : atomStrings) {
            atomString = atomString.trim();
            if (atomString.isEmpty()) continue;
            
            // Parse class atom: ClassName(?var)
            if (atomString.matches("\\w+\\(\\?\\w+\\)")) {
                atoms.add(parseClassAtom(ontology, atomString));
            }
            // Parse property atom: propertyName(?var1, ?var2)
            else if (atomString.matches("\\w+\\(\\?\\w+,\\s*\\?\\w+\\)")) {
                atoms.add(parsePropertyAtom(ontology, atomString));
            }
        }
        
        return atoms;
    }

    /**
     * Parse class atom
     */
    private SWRLAtom parseClassAtom(OWLOntology ontology, String atomString) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        
        // Extract class name and variable
        int openParen = atomString.indexOf('(');
        int closeParen = atomString.indexOf(')');
        
        String className = atomString.substring(0, openParen).trim();
        String varName = atomString.substring(openParen + 1, closeParen).trim();
        
        // Create IRI for class (assuming it's in the ontology's namespace)
        IRI classIRI = IRI.create(ontology.getOntologyID().getOntologyIRI().orElse(IRI.create("http://example.org/")) + "#" + className);
        OWLClass owlClass = df.getOWLClass(classIRI);
        
        // Create variable
        SWRLVariable variable = df.getSWRLVariable(IRI.create("urn:swrl:var#" + varName.substring(1))); // Remove ?
        
        return df.getSWRLClassAtom(owlClass, variable);
    }

    /**
     * Parse property atom
     */
    private SWRLAtom parsePropertyAtom(OWLOntology ontology, String atomString) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        
        // Extract property name and variables
        int openParen = atomString.indexOf('(');
        int closeParen = atomString.indexOf(')');
        int comma = atomString.indexOf(',');
        
        String propertyName = atomString.substring(0, openParen).trim();
        String var1Name = atomString.substring(openParen + 1, comma).trim();
        String var2Name = atomString.substring(comma + 1, closeParen).trim();
        
        // Create IRI for property
        IRI propertyIRI = IRI.create(ontology.getOntologyID().getOntologyIRI().orElse(IRI.create("http://example.org/")) + "#" + propertyName);
        OWLObjectProperty property = df.getOWLObjectProperty(propertyIRI);
        
        // Create variables
        SWRLVariable var1 = df.getSWRLVariable(IRI.create("urn:swrl:var#" + var1Name.substring(1)));
        SWRLVariable var2 = df.getSWRLVariable(IRI.create("urn:swrl:var#" + var2Name.substring(1)));
        
        return df.getSWRLObjectPropertyAtom(property, var1, var2);
    }

    /**
     * Get all variables used in a rule
     */
    public Set<SWRLVariable> getVariables(SWRLRule rule) {
        SWRLVariableExtractor extractor = new SWRLVariableExtractor();
        return rule.accept(extractor);
    }

    /**
     * Validate a SWRL rule
     */
    public ValidationResult validateRule(OWLOntology ontology, SWRLRule rule) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            // Check if all classes exist
            for (OWLClass owlClass : rule.getClassesInSignature()) {
                if (!ontology.containsClassInSignature(owlClass.getIRI())) {
                    warnings.add("Class not found in ontology: " + owlClass.getIRI().getShortForm());
                }
            }

            // Check if all properties exist
            for (OWLObjectProperty property : rule.getObjectPropertiesInSignature()) {
                if (!ontology.containsObjectPropertyInSignature(property.getIRI())) {
                    warnings.add("Object property not found in ontology: " + property.getIRI().getShortForm());
                }
            }

            for (OWLDataProperty property : rule.getDataPropertiesInSignature()) {
                if (!ontology.containsDataPropertyInSignature(property.getIRI())) {
                    warnings.add("Data property not found in ontology: " + property.getIRI().getShortForm());
                }
            }

            // Check if variables in head are used in body
            Set<SWRLVariable> bodyVars = new HashSet<>();
            for (SWRLAtom atom : rule.getBody()) {
                bodyVars.addAll(atom.accept(new SWRLVariableExtractor()));
            }

            Set<SWRLVariable> headVars = new HashSet<>();
            for (SWRLAtom atom : rule.getHead()) {
                headVars.addAll(atom.accept(new SWRLVariableExtractor()));
            }

            for (SWRLVariable headVar : headVars) {
                if (!bodyVars.contains(headVar)) {
                    errors.add("Variable in head not present in body: " + headVar.getIRI().getShortForm());
                }
            }

            // Check for empty body or head
            if (rule.getBody().isEmpty()) {
                errors.add("Rule body is empty");
            }
            if (rule.getHead().isEmpty()) {
                errors.add("Rule head is empty");
            }

        } catch (Exception e) {
            errors.add("Validation error: " + e.getMessage());
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * Format SWRL rule to readable string
     */
    public String formatRule(SWRLRule rule) {
        StringBuilder sb = new StringBuilder();
        
        // Format body
        List<String> bodyAtoms = rule.getBody().stream()
            .map(this::formatAtom)
            .collect(Collectors.toList());
        sb.append(String.join(" ∧ ", bodyAtoms));
        
        sb.append(" → ");
        
        // Format head
        List<String> headAtoms = rule.getHead().stream()
            .map(this::formatAtom)
            .collect(Collectors.toList());
        sb.append(String.join(" ∧ ", headAtoms));
        
        return sb.toString();
    }

    /**
     * Format individual atom to string
     */
    private String formatAtom(SWRLAtom atom) {
        if (atom instanceof SWRLClassAtom) {
            SWRLClassAtom classAtom = (SWRLClassAtom) atom;
            return classAtom.getPredicate().getIRI().getShortForm() + 
                   "(" + formatArgument(classAtom.getArgument()) + ")";
        }
        else if (atom instanceof SWRLObjectPropertyAtom) {
            SWRLObjectPropertyAtom propAtom = (SWRLObjectPropertyAtom) atom;
            return propAtom.getPredicate().getNamedProperty().getIRI().getShortForm() + 
                   "(" + formatArgument(propAtom.getFirstArgument()) + ", " + 
                   formatArgument(propAtom.getSecondArgument()) + ")";
        }
        else if (atom instanceof SWRLDataPropertyAtom) {
            SWRLDataPropertyAtom propAtom = (SWRLDataPropertyAtom) atom;
            return propAtom.getPredicate().getIRI().getShortForm() + 
                   "(" + formatArgument(propAtom.getFirstArgument()) + ", " + 
                   formatDArgument(propAtom.getSecondArgument()) + ")";
        }
        
        return atom.toString();
    }

    /**
     * Format SWRL argument (individual or variable)
     */
    private String formatArgument(SWRLIArgument arg) {
        if (arg instanceof SWRLVariable) {
            return "?" + ((SWRLVariable) arg).getIRI().getShortForm();
        } else if (arg instanceof SWRLIndividualArgument) {
            return ((SWRLIndividualArgument) arg).getIndividual().toStringID();
        }
        return arg.toString();
    }

    /**
     * Format SWRL data argument
     */
    private String formatDArgument(SWRLDArgument arg) {
        if (arg instanceof SWRLVariable) {
            return "?" + ((SWRLVariable) arg).getIRI().getShortForm();
        } else if (arg instanceof SWRLLiteralArgument) {
            return ((SWRLLiteralArgument) arg).getLiteral().getLiteral();
        }
        return arg.toString();
    }

    /**
     * Get statistics about SWRL rules in ontology
     */
    public Map<String, Object> getRuleStatistics(OWLOntology ontology) {
        Set<SWRLRule> rules = getAllRules(ontology);
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRules", rules.size());
        
        // Count unique variables
        Set<SWRLVariable> allVariables = new HashSet<>();
        for (SWRLRule rule : rules) {
            allVariables.addAll(getVariables(rule));
        }
        stats.put("uniqueVariables", allVariables.size());
        
        // Count atom types
        int classAtoms = 0;
        int objectPropertyAtoms = 0;
        int dataPropertyAtoms = 0;
        
        for (SWRLRule rule : rules) {
            for (SWRLAtom atom : rule.getBody()) {
                if (atom instanceof SWRLClassAtom) classAtoms++;
                else if (atom instanceof SWRLObjectPropertyAtom) objectPropertyAtoms++;
                else if (atom instanceof SWRLDataPropertyAtom) dataPropertyAtoms++;
            }
            for (SWRLAtom atom : rule.getHead()) {
                if (atom instanceof SWRLClassAtom) classAtoms++;
                else if (atom instanceof SWRLObjectPropertyAtom) objectPropertyAtoms++;
                else if (atom instanceof SWRLDataPropertyAtom) dataPropertyAtoms++;
            }
        }
        
        stats.put("classAtoms", classAtoms);
        stats.put("objectPropertyAtoms", objectPropertyAtoms);
        stats.put("dataPropertyAtoms", dataPropertyAtoms);
        
        return stats;
    }

    /**
     * Validation result class
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors;
            this.warnings = warnings;
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}