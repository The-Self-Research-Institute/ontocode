package self.research.ontology.owlEditor.util;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLRule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Serializes OWLAPI axioms to SPARQL INSERT DATA for GraphDB mutation.
 */
public final class OwlAxiomSparqlWriter {

    private static final String PREFIXES = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
        """;

    private OwlAxiomSparqlWriter() {}

    public static String toInsertData(Set<? extends OWLAxiom> axioms) throws Exception {
        if (axioms == null || axioms.isEmpty()) {
            return "";
        }

        OWLOntologyManager tempManager = OWLManager.createOWLOntologyManager();
        OWLOntology tempOntology = tempManager.createOntology(
                IRI.create("urn:ontocode:axiom-add:" + UUID.randomUUID()));
        tempManager.addAxioms(tempOntology, axioms);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tempManager.saveOntology(tempOntology, new RDFXMLDocumentFormat(), out);

        var model = Rio.parse(
                new ByteArrayInputStream(out.toByteArray()),
                "",
                RDFFormat.RDFXML);

        StringBuilder sparql = new StringBuilder(PREFIXES).append("INSERT DATA {\n");
        for (var st : model) {
            if (isTemporaryOntologyHeader(st)) {
                continue;
            }
            sparql.append("  ")
                    .append(toSparqlTerm(st.getSubject()))
                    .append(" ")
                    .append(toSparqlTerm(st.getPredicate()))
                    .append(" ")
                    .append(toSparqlTerm(st.getObject()))
                    .append(" .\n");
        }
        sparql.append("}");
        return sparql.toString();
    }

    public static String toDeleteData(Set<? extends OWLAxiom> axioms) throws Exception {
        if (axioms == null || axioms.isEmpty()) {
            return "";
        }
        if (axioms.stream().anyMatch(a -> a instanceof SWRLRule)) {
            throw new IllegalArgumentException("SWRL rules contain blank nodes and must be deleted with toDeleteWhere");
        }

        OWLOntologyManager tempManager = OWLManager.createOWLOntologyManager();
        OWLOntology tempOntology = tempManager.createOntology(
                IRI.create("urn:ontocode:axiom-del:" + UUID.randomUUID()));
        tempManager.addAxioms(tempOntology, axioms);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tempManager.saveOntology(tempOntology, new RDFXMLDocumentFormat(), out);

        var model = Rio.parse(
                new ByteArrayInputStream(out.toByteArray()),
                "",
                RDFFormat.RDFXML);

        StringBuilder sparql = new StringBuilder(PREFIXES).append("DELETE DATA {\n");
        for (var st : model) {
            if (isTemporaryOntologyHeader(st)) {
                continue;
            }
            sparql.append("  ")
                    .append(toSparqlTerm(st.getSubject()))
                    .append(" ")
                    .append(toSparqlTerm(st.getPredicate()))
                    .append(" ")
                    .append(toSparqlTerm(st.getObject()))
                    .append(" .\n");
        }
        sparql.append("}");
        return sparql.toString();
    }
public static String toDeleteWhere(Set<? extends OWLAxiom> axioms) throws Exception {
    if (axioms == null || axioms.isEmpty()) {
        return "";
    }
    if (axioms.stream().anyMatch(a -> a instanceof SWRLRule)) {
        StringBuilder all = new StringBuilder();
        for (OWLAxiom axiom : axioms) {
            if (axiom instanceof SWRLRule rule) {
                all.append(all.length() == 0 ? "" : ";\n").append(toDeleteRule(rule));
            } else {
                all.append(all.length() == 0 ? "" : ";\n").append(toDeleteWhere(Set.of(axiom)));
            }
        }
        return all.toString();
    }

    OWLOntologyManager tempManager = OWLManager.createOWLOntologyManager();
    OWLOntology tempOntology = tempManager.createOntology(
            IRI.create("urn:ontocode:axiom-del:" + UUID.randomUUID()));
    tempManager.addAxioms(tempOntology, axioms);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    tempManager.saveOntology(tempOntology, new RDFXMLDocumentFormat(), out);

    var model = Rio.parse(
            new ByteArrayInputStream(out.toByteArray()),
            "",
            RDFFormat.RDFXML);

    java.util.Map<String, String> bNodeVars = new java.util.HashMap<>();
    StringBuilder pattern = new StringBuilder();
    for (var st : model) {
        if (isTemporaryOntologyHeader(st)) {
            continue;
        }
        pattern.append("  ")
                .append(toSparqlPatternTerm(st.getSubject(), bNodeVars))
                .append(" ")
                .append(toSparqlPatternTerm(st.getPredicate(), bNodeVars))
                .append(" ")
                .append(toSparqlPatternTerm(st.getObject(), bNodeVars))
                .append(" .\n");
    }

    String patternStr = pattern.toString();
    return PREFIXES + "DELETE {\n" + patternStr + "}\nWHERE {\n" + patternStr + "}";
}

public static String toDeleteRule(SWRLRule rule) throws Exception {
    OWLOntologyManager tempManager = OWLManager.createOWLOntologyManager();
    OWLOntology tempOntology = tempManager.createOntology(
            IRI.create("urn:ontocode:axiom-del:" + UUID.randomUUID()));
    tempManager.addAxioms(tempOntology, Set.of(rule));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    tempManager.saveOntology(tempOntology, new RDFXMLDocumentFormat(), out);
    var model = Rio.parse(new ByteArrayInputStream(out.toByteArray()), "", RDFFormat.RDFXML);

    java.util.Map<String, String> bNodeVars = new java.util.HashMap<>();
    StringBuilder pattern = new StringBuilder();
    String impVar = null;
    org.eclipse.rdf4j.model.Resource impNode = null;
    for (var st : model) {
        if (st.getSubject() instanceof org.eclipse.rdf4j.model.BNode
                && st.getPredicate().stringValue().equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
                && st.getObject().stringValue().equals("http://www.w3.org/2003/11/swrl#Imp")) {
            impNode = st.getSubject();
        }
    }
    java.util.Set<String> impPredicates = new java.util.LinkedHashSet<>();
    for (var st : model) {
        if (!(st.getSubject() instanceof org.eclipse.rdf4j.model.BNode)) {
            continue;
        }
        String subject = toSparqlPatternTerm(st.getSubject(), bNodeVars);
        if (st.getSubject().equals(impNode)) {
            impVar = subject;
            impPredicates.add("<" + st.getPredicate().stringValue() + ">");
        }
        pattern.append("  ").append(subject).append(" ")
                .append(toSparqlPatternTerm(st.getPredicate(), bNodeVars)).append(" ")
                .append(toSparqlPatternTerm(st.getObject(), bNodeVars)).append(" .\n");
    }
    if (impVar == null) {
        throw new IllegalStateException("The rule could not be serialised as a swrl:Imp");
    }
    String guard = "  FILTER NOT EXISTS { " + impVar + " ?guardP ?guardO . FILTER(?guardP NOT IN ("
            + String.join(", ", impPredicates) + ")) }\n"
            + "  FILTER NOT EXISTS { ?ruleAnnotationNode owl:annotatedSource " + impVar + " }\n";
    String patternStr = pattern.toString();
    return PREFIXES + "DELETE {\n" + patternStr + "}\nWHERE {\n" + patternStr + guard + "}";
}

/** Like toSparqlTerm, but renders blank nodes as SPARQL variables so the same pattern
 *  can be used structurally in both the DELETE and WHERE clauses. */
private static String toSparqlPatternTerm(Value value, java.util.Map<String, String> bNodeVars) {
    if (value instanceof org.eclipse.rdf4j.model.BNode bNode) {
        return bNodeVars.computeIfAbsent(bNode.getID(), id -> "?bn_" + bNodeVars.size());
    }
    return toSparqlTerm(value);
}
    private static boolean isTemporaryOntologyHeader(org.eclipse.rdf4j.model.Statement st) {
        return st.getPredicate().stringValue().equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
                && st.getObject().stringValue().equals("http://www.w3.org/2002/07/owl#Ontology");
    }

    /** Renders a single RDF4J term as SPARQL syntax suitable for an INSERT/DELETE DATA block. */
    public static String toSparqlTerm(Value value) {
        if (value instanceof org.eclipse.rdf4j.model.IRI iri) {
            return "<" + iri.stringValue() + ">";
        }
        if (value instanceof org.eclipse.rdf4j.model.BNode bNode) {
            return "_:axiom_" + bNode.getID().replaceAll("[^A-Za-z0-9_]", "_");
        }
        if (value instanceof org.eclipse.rdf4j.model.Literal literal) {
            String escaped = literal.getLabel()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
            StringBuilder term = new StringBuilder("\"").append(escaped).append("\"");
            literal.getLanguage().ifPresent(lang -> term.append("@").append(lang));
            if (literal.getLanguage().isEmpty() && literal.getDatatype() != null) {
                term.append("^^<").append(literal.getDatatype().stringValue()).append(">");
            }
            return term.toString();
        }
        return "\"" + value.stringValue().replace("\"", "\\\"") + "\"";
    }
}
