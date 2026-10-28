package self.research.ontology.common;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataRange;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLBuiltInAtom;
import org.semanticweb.owlapi.model.SWRLClassAtom;
import org.semanticweb.owlapi.model.SWRLDArgument;
import org.semanticweb.owlapi.model.SWRLDataPropertyAtom;
import org.semanticweb.owlapi.model.SWRLIArgument;
import org.semanticweb.owlapi.model.SWRLLiteralArgument;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLFacet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SwrlComparisonTranslator {

    private static final String SWRLB = "http://www.w3.org/2003/11/swrlb#";

    private static final Set<OWL2Datatype> EXACT_NUMBERS = Set.of(
            OWL2Datatype.XSD_INTEGER, OWL2Datatype.XSD_DECIMAL, OWL2Datatype.XSD_NON_NEGATIVE_INTEGER,
            OWL2Datatype.XSD_NON_POSITIVE_INTEGER, OWL2Datatype.XSD_POSITIVE_INTEGER,
            OWL2Datatype.XSD_NEGATIVE_INTEGER, OWL2Datatype.XSD_LONG, OWL2Datatype.XSD_INT,
            OWL2Datatype.XSD_SHORT, OWL2Datatype.XSD_BYTE, OWL2Datatype.XSD_UNSIGNED_LONG,
            OWL2Datatype.XSD_UNSIGNED_INT, OWL2Datatype.XSD_UNSIGNED_SHORT, OWL2Datatype.XSD_UNSIGNED_BYTE);

    private static final Set<OWL2Datatype> FLOATING_NUMBERS = Set.of(OWL2Datatype.XSD_DOUBLE, OWL2Datatype.XSD_FLOAT);

    private enum Op { GREATER, GREATER_OR_EQUAL, LESS, LESS_OR_EQUAL, EQUAL, NOT_EQUAL }

    private record Comparison(Op op, OWLLiteral constant) {
    }

    private SwrlComparisonTranslator() {
    }

    public static boolean isComparison(IRI builtIn) {
        return operation(builtIn).isPresent();
    }

    public static Optional<OWLAxiom> translate(SWRLRule rule, OWLDataFactory df) {
        List<SWRLAtom> head = rule.head().toList();
        if (head.size() != 1 || !(head.get(0) instanceof SWRLClassAtom headAtom)) {
            return Optional.empty();
        }
        if (headAtom.getPredicate().isAnonymous() || !(headAtom.getArgument() instanceof SWRLVariable subject)) {
            return Optional.empty();
        }
        OWLClass headClass = headAtom.getPredicate().asOWLClass();

        List<OWLClassExpression> operands = new ArrayList<>();
        Map<IRI, OWLDataProperty> propertyOfValue = new LinkedHashMap<>();
        Map<IRI, List<Comparison>> comparisons = new LinkedHashMap<>();
        List<SWRLBuiltInAtom> builtIns = new ArrayList<>();

        for (SWRLAtom atom : rule.body().toList()) {
            if (atom instanceof SWRLClassAtom classAtom) {
                if (classAtom.getPredicate().isAnonymous() || !sameVariable(classAtom.getArgument(), subject)) {
                    return Optional.empty();
                }
                operands.add(classAtom.getPredicate());
            } else if (atom instanceof SWRLDataPropertyAtom propertyAtom) {
                if (propertyAtom.getPredicate().isAnonymous() || !sameVariable(propertyAtom.getFirstArgument(), subject)
                        || !(propertyAtom.getSecondArgument() instanceof SWRLVariable value)
                        || value.getIRI().equals(subject.getIRI())
                        || propertyOfValue.containsKey(value.getIRI())) {
                    return Optional.empty();
                }
                propertyOfValue.put(value.getIRI(), propertyAtom.getPredicate().asOWLDataProperty());
            } else if (atom instanceof SWRLBuiltInAtom builtIn) {
                builtIns.add(builtIn);
            } else {
                return Optional.empty();
            }
        }
        if (propertyOfValue.isEmpty() || builtIns.isEmpty()) {
            return Optional.empty();
        }

        for (SWRLBuiltInAtom builtIn : builtIns) {
            Optional<Op> op = operation(builtIn.getPredicate());
            List<SWRLDArgument> arguments = builtIn.getArguments();
            if (op.isEmpty() || arguments.size() != 2) {
                return Optional.empty();
            }
            Op operation = op.get();
            SWRLDArgument first = arguments.get(0);
            SWRLDArgument second = arguments.get(1);
            SWRLVariable variable;
            OWLLiteral constant;
            if (first instanceof SWRLVariable v && second instanceof SWRLLiteralArgument c) {
                variable = v;
                constant = c.getLiteral();
            } else if (first instanceof SWRLLiteralArgument c && second instanceof SWRLVariable v) {
                variable = v;
                constant = c.getLiteral();
                operation = flipped(operation);
            } else {
                return Optional.empty();
            }
            if (!propertyOfValue.containsKey(variable.getIRI())) {
                return Optional.empty();
            }
            comparisons.computeIfAbsent(variable.getIRI(), k -> new ArrayList<>()).add(new Comparison(operation, constant));
        }

        for (Map.Entry<IRI, OWLDataProperty> entry : propertyOfValue.entrySet()) {
            List<Comparison> own = comparisons.get(entry.getKey());
            if (own == null) {
                operands.add(df.getOWLDataSomeValuesFrom(entry.getValue(), df.getTopDatatype()));
                continue;
            }
            List<OWLDataRange> ranges = new ArrayList<>();
            for (Comparison comparison : own) {
                Optional<OWLDataRange> range = rangeOf(comparison, df);
                if (range.isEmpty()) {
                    return Optional.empty();
                }
                ranges.add(range.get());
            }
            OWLDataRange combined = ranges.size() == 1 ? ranges.get(0) : df.getOWLDataIntersectionOf(ranges);
            operands.add(df.getOWLDataSomeValuesFrom(entry.getValue(), combined));
        }

        OWLClassExpression body = operands.size() == 1 ? operands.get(0) : df.getOWLObjectIntersectionOf(operands);
        return Optional.of(df.getOWLSubClassOfAxiom(body, headClass));
    }

    private static Optional<Op> operation(IRI builtIn) {
        String text = builtIn.toString();
        if (!text.startsWith(SWRLB)) {
            return Optional.empty();
        }
        return switch (text.substring(SWRLB.length())) {
            case "greaterThan" -> Optional.of(Op.GREATER);
            case "greaterThanOrEqual" -> Optional.of(Op.GREATER_OR_EQUAL);
            case "lessThan" -> Optional.of(Op.LESS);
            case "lessThanOrEqual" -> Optional.of(Op.LESS_OR_EQUAL);
            case "equal" -> Optional.of(Op.EQUAL);
            case "notEqual" -> Optional.of(Op.NOT_EQUAL);
            default -> Optional.empty();
        };
    }

    private static Op flipped(Op op) {
        return switch (op) {
            case GREATER -> Op.LESS;
            case GREATER_OR_EQUAL -> Op.LESS_OR_EQUAL;
            case LESS -> Op.GREATER;
            case LESS_OR_EQUAL -> Op.GREATER_OR_EQUAL;
            default -> op;
        };
    }

    private static boolean sameVariable(SWRLIArgument argument, SWRLVariable variable) {
        return argument instanceof SWRLVariable other && other.getIRI().equals(variable.getIRI());
    }

    private static Optional<OWLDataRange> rangeOf(Comparison comparison, OWLDataFactory df) {
        OWLLiteral constant = comparison.constant();
        OWLDatatype type = constant.getDatatype();
        boolean exact = EXACT_NUMBERS.stream().anyMatch(t -> t.getIRI().equals(type.getIRI()));
        boolean floating = FLOATING_NUMBERS.stream().anyMatch(t -> t.getIRI().equals(type.getIRI()));
        if (exact || floating) {
            OWLDatatype base = exact ? df.getOWLDatatype(OWL2Datatype.XSD_DECIMAL.getIRI()) : type;
            OWLLiteral value = df.getOWLLiteral(constant.getLiteral(), base);
            return switch (comparison.op()) {
                case GREATER -> Optional.of(df.getOWLDatatypeRestriction(base, df.getOWLFacetRestriction(OWLFacet.MIN_EXCLUSIVE, value)));
                case GREATER_OR_EQUAL -> Optional.of(df.getOWLDatatypeRestriction(base, df.getOWLFacetRestriction(OWLFacet.MIN_INCLUSIVE, value)));
                case LESS -> Optional.of(df.getOWLDatatypeRestriction(base, df.getOWLFacetRestriction(OWLFacet.MAX_EXCLUSIVE, value)));
                case LESS_OR_EQUAL -> Optional.of(df.getOWLDatatypeRestriction(base, df.getOWLFacetRestriction(OWLFacet.MAX_INCLUSIVE, value)));
                case EQUAL -> Optional.of(df.getOWLDataOneOf(value));
                case NOT_EQUAL -> Optional.of(df.getOWLDataComplementOf(df.getOWLDataOneOf(value)));
            };
        }
        return switch (comparison.op()) {
            case EQUAL -> Optional.of(df.getOWLDataOneOf(constant));
            case NOT_EQUAL -> Optional.of(df.getOWLDataComplementOf(df.getOWLDataOneOf(constant)));
            default -> Optional.empty();
        };
    }
}
