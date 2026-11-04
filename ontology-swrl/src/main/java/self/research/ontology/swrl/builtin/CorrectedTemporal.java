package self.research.ontology.swrl.builtin;

import org.swrlapi.builtins.arguments.SWRLBuiltInArgument;
import org.swrlapi.exceptions.SWRLBuiltInException;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

public class CorrectedTemporal extends org.swrlapi.builtins.temporal.SWRLBuiltInLibraryImpl {

    private boolean literalOperands(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return a.size() == 4 && isArgumentALiteral(0, a) && isArgumentALiteral(1, a);
    }

    private ChronoUnit unit(String granularity) throws SWRLBuiltInException {
        String name = granularity.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith("s")) {
            name = name.substring(0, name.length() - 1);
        }
        return switch (name) {
            case "year" -> ChronoUnit.YEARS;
            case "month" -> ChronoUnit.MONTHS;
            case "day" -> ChronoUnit.DAYS;
            case "hour" -> ChronoUnit.HOURS;
            case "minute" -> ChronoUnit.MINUTES;
            case "second" -> ChronoUnit.SECONDS;
            case "millisecond" -> ChronoUnit.MILLIS;
            default -> throw new SWRLBuiltInException("'" + granularity + "' is not a unit of time; use Years, Months, Days, Hours, Minutes, Seconds or Milliseconds");
        };
    }

    private int compare(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        checkForUnboundArguments(a);
        try {
            XsdValues.DateTime first = XsdValues.parseDateOrDateTime(getArgumentAsAnOWLLiteral(0, a).getLiteral());
            XsdValues.DateTime second = XsdValues.parseDateOrDateTime(getArgumentAsAnOWLLiteral(1, a).getLiteral());
            BigDecimal amount = getArgumentAsADecimal(2, a);
            long between = Math.abs(unit(getArgumentAsAString(3, a)).between(first.utc(), second.utc()));
            return BigDecimal.valueOf(between).compareTo(amount);
        } catch (IllegalArgumentException e) {
            throw new SWRLBuiltInException(e.getMessage());
        }
    }

    @Override
    public boolean durationLessThan(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return literalOperands(a) ? compare(a) < 0 : super.durationLessThan(a);
    }

    @Override
    public boolean durationLessThanOrEqualTo(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return literalOperands(a) ? compare(a) <= 0 : super.durationLessThanOrEqualTo(a);
    }

    @Override
    public boolean durationEqualTo(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return literalOperands(a) ? compare(a) == 0 : super.durationEqualTo(a);
    }

    @Override
    public boolean durationGreaterThan(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return literalOperands(a) ? compare(a) > 0 : super.durationGreaterThan(a);
    }

    @Override
    public boolean durationGreaterThanOrEqualTo(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return literalOperands(a) ? compare(a) >= 0 : super.durationGreaterThanOrEqualTo(a);
    }

    @Override
    public boolean notDurationLessThan(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return literalOperands(a) ? compare(a) >= 0 : super.notDurationLessThan(a);
    }

    @Override
    public boolean notDurationLessThanOrEqualTo(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return literalOperands(a) ? compare(a) > 0 : super.notDurationLessThanOrEqualTo(a);
    }

    @Override
    public boolean notDurationEqualTo(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return literalOperands(a) ? compare(a) != 0 : super.notDurationEqualTo(a);
    }

    @Override
    public boolean notDurationGreaterThan(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return literalOperands(a) ? compare(a) <= 0 : super.notDurationGreaterThan(a);
    }

    @Override
    public boolean notDurationGreaterThanOrEqualTo(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return literalOperands(a) ? compare(a) < 0 : super.notDurationGreaterThanOrEqualTo(a);
    }
}
