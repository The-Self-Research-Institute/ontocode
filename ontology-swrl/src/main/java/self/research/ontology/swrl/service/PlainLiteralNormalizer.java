package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLNegativeDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLBuiltInAtom;
import org.semanticweb.owlapi.model.SWRLDArgument;
import org.semanticweb.owlapi.model.SWRLDataPropertyAtom;
import org.semanticweb.owlapi.model.SWRLLiteralArgument;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PlainLiteralNormalizer {

    private static final Pattern DOUBLE_ONLY_AGGREGATE = Pattern.compile("sqwrl:(sum|avg|median)\\s*\\(");
    private static final Set<OWL2Datatype> EXACT_NUMBERS = Set.of(OWL2Datatype.XSD_INTEGER, OWL2Datatype.XSD_INT,
            OWL2Datatype.XSD_LONG, OWL2Datatype.XSD_SHORT, OWL2Datatype.XSD_BYTE, OWL2Datatype.XSD_DECIMAL,
            OWL2Datatype.XSD_FLOAT, OWL2Datatype.XSD_NON_NEGATIVE_INTEGER, OWL2Datatype.XSD_POSITIVE_INTEGER,
            OWL2Datatype.XSD_NON_POSITIVE_INTEGER, OWL2Datatype.XSD_NEGATIVE_INTEGER, OWL2Datatype.XSD_UNSIGNED_INT,
            OWL2Datatype.XSD_UNSIGNED_LONG, OWL2Datatype.XSD_UNSIGNED_SHORT, OWL2Datatype.XSD_UNSIGNED_BYTE);

    private PlainLiteralNormalizer() {
    }

    static int apply(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        int changed = 0;
        Set<OWLAxiom> stale = new HashSet<>();
        Set<OWLAxiom> typed = new HashSet<>();
        for (OWLDataPropertyAssertionAxiom axiom : ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION)) {
            if (isPlain(axiom.getObject())) {
                stale.add(axiom);
                typed.add(df.getOWLDataPropertyAssertionAxiom(axiom.getProperty(), axiom.getSubject(),
                        typed(df, axiom.getObject()), axiom.getAnnotations()));
            }
        }
        for (OWLNegativeDataPropertyAssertionAxiom axiom : ontology.getAxioms(AxiomType.NEGATIVE_DATA_PROPERTY_ASSERTION)) {
            if (isPlain(axiom.getObject())) {
                stale.add(axiom);
                typed.add(df.getOWLNegativeDataPropertyAssertionAxiom(axiom.getProperty(), axiom.getSubject(),
                        typed(df, axiom.getObject()), axiom.getAnnotations()));
            }
        }
        if (!stale.isEmpty()) {
            manager.removeAxioms(ontology, stale);
            manager.addAxioms(ontology, typed);
            changed += stale.size();
        }
        List<SWRLRule> rules = new ArrayList<>(ontology.getAxioms(AxiomType.SWRL_RULE));
        for (SWRLRule rule : rules) {
            Set<SWRLAtom> body = retype(df, rule.getBody());
            Set<SWRLAtom> head = retype(df, rule.getHead());
            if (body != null || head != null) {
                manager.removeAxiom(ontology, rule);
                manager.addAxiom(ontology, df.getSWRLRule(body != null ? body : rule.getBody(),
                        head != null ? head : rule.getHead(), rule.getAnnotations()));
                changed++;
            }
        }
        return changed;
    }

    static boolean needsDoubleNumbers(String queryText) {
        return queryText != null && DOUBLE_ONLY_AGGREGATE.matcher(queryText).find();
    }

    static int numbersAsDouble(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        Set<OWLAxiom> stale = new HashSet<>();
        Set<OWLAxiom> converted = new HashSet<>();
        for (OWLDataPropertyAssertionAxiom axiom : ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION)) {
            OWLLiteral literal = axiom.getObject();
            if (!literal.getDatatype().isBuiltIn() || !EXACT_NUMBERS.contains(literal.getDatatype().getBuiltInDatatype())) {
                continue;
            }
            try {
                double value = Double.parseDouble(literal.getLiteral().trim());
                stale.add(axiom);
                converted.add(df.getOWLDataPropertyAssertionAxiom(axiom.getProperty(), axiom.getSubject(),
                        df.getOWLLiteral(value), axiom.getAnnotations()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (!stale.isEmpty()) {
            manager.removeAxioms(ontology, stale);
            manager.addAxioms(ontology, converted);
        }
        return stale.size();
    }

    private static Set<SWRLAtom> retype(OWLDataFactory df, Set<SWRLAtom> atoms) {
        boolean any = false;
        Set<SWRLAtom> result = new LinkedHashSet<>();
        for (SWRLAtom atom : atoms) {
            if (atom instanceof SWRLBuiltInAtom builtIn) {
                List<SWRLDArgument> arguments = new ArrayList<>();
                boolean changed = false;
                for (SWRLDArgument argument : builtIn.getArguments()) {
                    if (argument instanceof SWRLLiteralArgument literal && isPlain(literal.getLiteral())) {
                        arguments.add(df.getSWRLLiteralArgument(typed(df, literal.getLiteral())));
                        changed = true;
                    } else {
                        arguments.add(argument);
                    }
                }
                if (changed) {
                    result.add(df.getSWRLBuiltInAtom(builtIn.getPredicate(), arguments));
                    any = true;
                    continue;
                }
            } else if (atom instanceof SWRLDataPropertyAtom propertyAtom
                    && propertyAtom.getSecondArgument() instanceof SWRLLiteralArgument literal
                    && isPlain(literal.getLiteral())) {
                result.add(df.getSWRLDataPropertyAtom(propertyAtom.getPredicate(), propertyAtom.getFirstArgument(),
                        df.getSWRLLiteralArgument(typed(df, literal.getLiteral()))));
                any = true;
                continue;
            }
            result.add(atom);
        }
        return any ? result : null;
    }

    private static boolean isPlain(OWLLiteral literal) {
        return literal.isRDFPlainLiteral() && !literal.hasLang();
    }

    private static OWLLiteral typed(OWLDataFactory df, OWLLiteral literal) {
        return df.getOWLLiteral(literal.getLiteral(), OWL2Datatype.XSD_STRING);
    }
}
