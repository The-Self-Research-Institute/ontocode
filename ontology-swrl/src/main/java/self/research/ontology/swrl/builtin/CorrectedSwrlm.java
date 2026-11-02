package self.research.ontology.swrl.builtin;

import org.nfunk.jep.JEP;
import org.swrlapi.builtins.arguments.SWRLBuiltInArgument;
import org.swrlapi.exceptions.SWRLBuiltInException;

import java.util.List;
import java.util.Optional;

public class CorrectedSwrlm extends org.swrlapi.builtins.swrlm.SWRLBuiltInLibraryImpl {

    private JEP jep;

    @Override
    public void reset() {
        super.reset();
        jep = null;
    }

    @Override
    public boolean log(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        if (a.size() != 3) {
            return super.log(a);
        }
        checkForUnboundNonFirstArguments(a);
        checkThatArgumentIsNumeric(1, a);
        checkThatArgumentIsNumeric(2, a);
        double x = number(1, a);
        double base = number(2, a);
        if (x <= 0 || base <= 0 || base == 1) {
            throw new SWRLBuiltInException("the logarithm needs a positive number and a positive base other than 1");
        }
        double result = base == 10 ? Math.log10(x) : Math.log(x) / Math.log(base);
        if (Math.abs(result - Math.rint(result)) < 1e-12) {
            result = Math.rint(result);
        }
        return processResultArgument(a, 0, result);
    }

    @Override
    public boolean eval(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        checkNumberOfArgumentsAtLeast(2, a.size());
        checkForUnboundNonFirstArguments(a);
        String formula = getArgumentAsAString(1, a);
        JEP engine = engine();
        if (a.size() > 2) {
            checkForUnboundArguments(a.subList(2, a.size()), "2nd and subsequent arguments cannot be unbound");
            checkThatAllArgumentsAreBoundVariables(a.subList(2, a.size()), "2nd and subsequent arguments should be variables");
            for (int i = 2; i < a.size(); i++) {
                Optional<String> name = a.get(i).getBoundVariableName();
                if (name.isPresent()) {
                    engine.addVariable(name.get(), number(i, a));
                }
            }
        }
        engine.parseExpression(formula);
        if (engine.hasError()) {
            throw new SWRLBuiltInException("the formula is not valid: " + engine.getErrorInfo());
        }
        double value = engine.getValue();
        if (engine.hasError()) {
            throw new SWRLBuiltInException("the formula could not be calculated: " + engine.getErrorInfo());
        }
        return processResultArgument(a, 0, value);
    }

    private double number(int i, List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        checkThatArgumentIsNumeric(i, a);
        return Double.parseDouble(getArgumentAsAnOWLLiteral(i, a).getLiteral().trim());
    }

    private JEP engine() {
        if (jep == null) {
            jep = new JEP();
            jep.addStandardFunctions();
            jep.addStandardConstants();
            jep.setImplicitMul(true);
        }
        return jep;
    }
}
