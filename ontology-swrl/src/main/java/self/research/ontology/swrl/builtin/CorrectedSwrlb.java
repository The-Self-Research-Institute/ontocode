package self.research.ontology.swrl.builtin;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.swrlapi.builtins.arguments.SWRLBuiltInArgument;
import org.swrlapi.exceptions.SWRLBuiltInException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class CorrectedSwrlb extends org.swrlapi.builtins.swrlb.SWRLBuiltInLibraryImpl {

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private static final Set<String> INTEGER_TYPES = Set.of("integer", "int", "long", "short", "byte", "nonNegativeInteger", "positiveInteger",
            "nonPositiveInteger", "negativeInteger", "unsignedLong", "unsignedInt", "unsignedShort", "unsignedByte");

    private final Set<String> names = new LinkedHashSet<>();

    public CorrectedSwrlb() {
        names.addAll(super.getBuiltInNames());
        names.add("subtractDateTimesYieldingDayTimeDuration");
    }

    @Override
    public Set<String> getBuiltInNames() {
        return names;
    }

    private String text(int i, List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return getArgumentAsAnOWLLiteral(i, a).getLiteral();
    }

    private boolean emit(List<SWRLBuiltInArgument> a, String lexical, String type) throws SWRLBuiltInException {
        return processResultArgument(a, 0, getSWRLAPIOWLDataFactory().getOWLLiteral(lexical, getSWRLAPIOWLDataFactory().getOWLDatatype(IRI.create(XSD + type))));
    }

    private boolean emitDuration(List<SWRLBuiltInArgument> a, String lexical) throws SWRLBuiltInException {
        return emit(a, lexical, "duration");
    }

    private void shape(List<SWRLBuiltInArgument> a, int size) throws SWRLBuiltInException {
        checkNumberOfArgumentsEqualTo(size, a.size());
        checkForUnboundNonFirstArguments(a);
    }

    private SWRLBuiltInException invalid(IllegalArgumentException e) {
        return new SWRLBuiltInException(e.getMessage());
    }

    private boolean isDoubleKind(int i, List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        OWLLiteral literal = getArgumentAsAnOWLLiteral(i, a);
        return literal.getDatatype().isDouble() || literal.getDatatype().isFloat();
    }

    private boolean isIntegerKind(int i, List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return INTEGER_TYPES.contains(getArgumentAsAnOWLLiteral(i, a).getDatatype().getIRI().getShortForm());
    }

    private long whole(int i, List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        BigDecimal value = getArgumentAsADecimal(i, a);
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new SWRLBuiltInException(value.toPlainString() + " is not a whole number in the range this function accepts");
        }
    }

    private XsdValues.Duration duration(int i, List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        try {
            return XsdValues.parseDuration(text(i, a));
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    private long yearMonth(int i, List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        XsdValues.Duration d = duration(i, a);
        if (d.hasSeconds()) {
            throw new SWRLBuiltInException(text(i, a) + " has days or time in it, but this function needs a duration of years and months only");
        }
        return d.months();
    }

    private BigDecimal dayTime(int i, List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        XsdValues.Duration d = duration(i, a);
        if (d.hasMonths()) {
            throw new SWRLBuiltInException(text(i, a) + " has years or months in it, but this function needs a duration of days and time only");
        }
        return d.seconds();
    }

    private boolean timeOperands(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return a.size() == 2 && isArgumentATime(0, a) && isArgumentATime(1, a);
    }

    private int compareTimes(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        try {
            return XsdValues.parseTime(text(0, a)).utcSeconds().compareTo(XsdValues.parseTime(text(1, a)).utcSeconds());
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @Override
    public boolean lessThan(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return timeOperands(a) ? compareTimes(a) < 0 : super.lessThan(a);
    }

    @Override
    public boolean lessThanOrEqual(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return timeOperands(a) ? compareTimes(a) <= 0 : super.lessThanOrEqual(a);
    }

    @Override
    public boolean greaterThan(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return timeOperands(a) ? compareTimes(a) > 0 : super.greaterThan(a);
    }

    @Override
    public boolean greaterThanOrEqual(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        return timeOperands(a) ? compareTimes(a) >= 0 : super.greaterThanOrEqual(a);
    }

    @Override
    public boolean divide(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        checkThatArgumentIsNumeric(1, a);
        checkThatArgumentIsNumeric(2, a);
        if (isDoubleKind(1, a) || isDoubleKind(2, a)) {
            return super.divide(a);
        }
        BigDecimal x = getArgumentAsADecimal(1, a);
        BigDecimal y = getArgumentAsADecimal(2, a);
        if (y.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        BigDecimal q;
        try {
            q = x.divide(y);
        } catch (ArithmeticException e) {
            q = x.divide(y, 20, RoundingMode.HALF_UP);
        }
        q = q.stripTrailingZeros();
        if (q.scale() < 0) {
            q = q.setScale(0);
        }
        if (q.scale() == 0 && isIntegerKind(1, a) && isIntegerKind(2, a)) {
            return processResultArgument(a, 0, q.toBigIntegerExact());
        }
        return processResultArgument(a, 0, q);
    }

    @Override
    public boolean mod(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        checkThatArgumentIsNumeric(1, a);
        checkThatArgumentIsNumeric(2, a);
        if (isDoubleKind(1, a) || isDoubleKind(2, a)) {
            return super.mod(a);
        }
        BigDecimal y = getArgumentAsADecimal(2, a);
        if (y.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        BigDecimal remainder = getArgumentAsADecimal(1, a).remainder(y);
        if (isIntegerKind(1, a) && isIntegerKind(2, a)) {
            return processResultArgument(a, 0, remainder.toBigIntegerExact());
        }
        return processResultArgument(a, 0, remainder.stripTrailingZeros().scale() < 0 ? remainder.setScale(0) : remainder.stripTrailingZeros());
    }

    @Override
    public boolean integerDivide(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        checkThatArgumentIsNumeric(1, a);
        checkThatArgumentIsNumeric(2, a);
        BigDecimal y = getArgumentAsADecimal(2, a);
        if (y.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        BigInteger q = getArgumentAsADecimal(1, a).divideToIntegralValue(y).toBigInteger();
        return processResultArgument(a, 0, q);
    }

    @Override
    public boolean round(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 2);
        checkThatArgumentIsNumeric(1, a);
        if (isIntegerKind(1, a)) {
            return processResultArgument(a, 0, getArgumentAsAnInteger(1, a));
        }
        BigDecimal rounded = XsdValues.roundHalfUp(getArgumentAsADecimal(1, a));
        if (isDoubleKind(1, a)) {
            return processResultArgument(a, 0, rounded.doubleValue());
        }
        return processResultArgument(a, 0, rounded);
    }

    @Override
    public boolean substring(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        checkNumberOfArgumentsInRange(3, 4, a.size());
        checkForUnboundNonFirstArguments(a);
        String s = getArgumentAsAString(1, a);
        BigDecimal start = XsdValues.roundHalfUp(getArgumentAsADecimal(2, a));
        BigDecimal end = a.size() == 4 ? start.add(XsdValues.roundHalfUp(getArgumentAsADecimal(3, a))) : null;
        StringBuilder out = new StringBuilder();
        for (int position = 1; position <= s.length(); position++) {
            BigDecimal p = BigDecimal.valueOf(position);
            if (p.compareTo(start) >= 0 && (end == null || p.compareTo(end) < 0)) {
                out.append(s.charAt(position - 1));
            }
        }
        return processResultArgument(a, 0, out.toString());
    }

    @Override
    public boolean substringBefore(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        String s = getArgumentAsAString(1, a);
        String find = getArgumentAsAString(2, a);
        int at = s.indexOf(find);
        return processResultArgument(a, 0, at < 0 ? "" : s.substring(0, at));
    }

    @Override
    public boolean translate(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 4);
        String s = getArgumentAsAString(1, a);
        String from = getArgumentAsAString(2, a);
        String to = getArgumentAsAString(3, a);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int at = from.indexOf(c);
            if (at < 0) {
                out.append(c);
            } else if (at < to.length()) {
                out.append(to.charAt(at));
            }
        }
        return processResultArgument(a, 0, out.toString());
    }

    @Override
    public boolean matches(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        checkNumberOfArgumentsInRange(2, 3, a.size());
        checkForUnboundArguments(a);
        String s = getArgumentAsAString(0, a);
        String pattern = getArgumentAsAString(1, a);
        int flags = 0;
        if (a.size() == 3) {
            for (char flag : getArgumentAsAString(2, a).toCharArray()) {
                switch (flag) {
                    case 'i' -> flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    case 's' -> flags |= Pattern.DOTALL;
                    case 'm' -> flags |= Pattern.MULTILINE;
                    case 'x' -> flags |= Pattern.COMMENTS;
                    case 'q' -> flags |= Pattern.LITERAL;
                    default -> throw new SWRLBuiltInException("'" + flag + "' is not a pattern option; use any of i, s, m, x, q");
                }
            }
        }
        try {
            return Pattern.compile(pattern, flags).matcher(s).find();
        } catch (PatternSyntaxException e) {
            throw new SWRLBuiltInException("invalid regular expression '" + pattern + "'");
        }
    }

    @Override
    public boolean anyURI(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        checkNumberOfArgumentsInRange(6, 7, a.size());
        checkForUnboundNonFirstArguments(a);
        String scheme = getArgumentAsAString(1, a);
        String host = getArgumentAsAString(2, a);
        String port = a.size() == 7 ? getArgumentAsAString(3, a) : "";
        int next = a.size() == 7 ? 4 : 3;
        String path = getArgumentAsAString(next, a);
        String query = getArgumentAsAString(next + 1, a);
        String fragment = getArgumentAsAString(next + 2, a);
        StringBuilder out = new StringBuilder();
        if (!scheme.isEmpty()) {
            out.append(scheme).append(':');
        }
        if (!host.isEmpty()) {
            out.append("//").append(host);
            if (!port.isEmpty()) {
                out.append(':').append(port);
            }
            if (!path.isEmpty() && !path.startsWith("/")) {
                out.append('/');
            }
        }
        out.append(path);
        if (!query.isEmpty()) {
            out.append('?').append(query);
        }
        if (!fragment.isEmpty()) {
            out.append('#').append(fragment);
        }
        try {
            new URI(out.toString());
        } catch (URISyntaxException e) {
            throw new SWRLBuiltInException(out + " is not a valid web address: " + e.getReason());
        }
        return emit(a, out.toString(), "anyURI");
    }

    @Override
    public boolean resolveURI(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        String relative = text(1, a);
        String base = text(2, a);
        try {
            URI baseUri = new URI(base.trim());
            if (!baseUri.isAbsolute()) {
                throw new SWRLBuiltInException(base + " is not a full web address, so " + relative + " cannot be resolved against it");
            }
            return emit(a, baseUri.resolve(new URI(relative.trim())).toString(), "anyURI");
        } catch (URISyntaxException e) {
            throw new SWRLBuiltInException("not a valid web address: " + e.getInput());
        }
    }

    @Override
    public boolean date(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 5);
        try {
            return emit(a, XsdValues.buildDate(whole(1, a), whole(2, a), whole(3, a), getArgumentAsAString(4, a)).format(), "date");
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @Override
    public boolean time(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 5);
        try {
            return emit(a, XsdValues.buildTime(whole(1, a), whole(2, a), getArgumentAsADecimal(3, a), getArgumentAsAString(4, a)).format(), "time");
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @Override
    public boolean dateTime(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 8);
        try {
            XsdValues.Date date = XsdValues.buildDate(whole(1, a), whole(2, a), whole(3, a), "");
            XsdValues.Time time = XsdValues.buildTime(whole(4, a), whole(5, a), getArgumentAsADecimal(6, a), getArgumentAsAString(7, a));
            return emit(a, XsdValues.buildDateTime(date, time).format(), "dateTime");
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @Override
    public boolean addYearMonthDurations(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        return emitDuration(a, XsdValues.formatYearMonth(yearMonth(1, a) + yearMonth(2, a)));
    }

    @Override
    public boolean subtractYearMonthDurations(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        return emitDuration(a, XsdValues.formatYearMonth(yearMonth(1, a) - yearMonth(2, a)));
    }

    @Override
    public boolean subtractDayTimeDurations(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        return emitDuration(a, XsdValues.formatDayTime(dayTime(1, a).subtract(dayTime(2, a))));
    }

    @Override
    public boolean multiplyYearMonthDuration(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        BigDecimal months = BigDecimal.valueOf(yearMonth(1, a)).multiply(getArgumentAsADecimal(2, a));
        return emitDuration(a, XsdValues.formatYearMonth(months(XsdValues.roundHalfUp(months))));
    }

    @Override
    public boolean divideYearMonthDuration(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        BigDecimal divisor = getArgumentAsADecimal(2, a);
        if (divisor.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        BigDecimal months = BigDecimal.valueOf(yearMonth(1, a)).divide(divisor, MathContext.DECIMAL128);
        return emitDuration(a, XsdValues.formatYearMonth(months(XsdValues.roundHalfUp(months))));
    }

    @Override
    public boolean multiplyDayTimeDuration(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        return emitDuration(a, XsdValues.formatDayTime(XsdValues.scaleSeconds(dayTime(1, a).multiply(getArgumentAsADecimal(2, a)))));
    }

    @Override
    public boolean divideDayTimeDuration(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        BigDecimal divisor = getArgumentAsADecimal(2, a);
        if (divisor.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return emitDuration(a, XsdValues.formatDayTime(XsdValues.scaleSeconds(dayTime(1, a).divide(divisor, 12, RoundingMode.HALF_UP))));
    }

    private long months(BigDecimal value) throws SWRLBuiltInException {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new SWRLBuiltInException("the result is too large for a duration");
        }
    }

    @Override
    public boolean subtractDates(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        try {
            LocalDateTime first = XsdValues.parseDate(text(1, a)).utcStart();
            LocalDateTime second = XsdValues.parseDate(text(2, a)).utcStart();
            return emitDuration(a, XsdValues.formatDayTime(XsdValues.secondsBetween(second, first)));
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @Override
    public boolean subtractTimes(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        try {
            return emitDuration(a, XsdValues.formatDayTime(XsdValues.parseTime(text(1, a)).utcSeconds().subtract(XsdValues.parseTime(text(2, a)).utcSeconds())));
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @Override
    public boolean subtractDateTimesYieldingYearMonthDuration(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        try {
            LocalDateTime first = XsdValues.parseDateTime(text(1, a)).utc();
            LocalDateTime second = XsdValues.parseDateTime(text(2, a)).utc();
            return emitDuration(a, XsdValues.formatYearMonth(ChronoUnit.MONTHS.between(second, first)));
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @Override
    public boolean subtractDateTimesYieldingDayTimeDuration(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        try {
            LocalDateTime first = XsdValues.parseDateTime(text(1, a)).utc();
            LocalDateTime second = XsdValues.parseDateTime(text(2, a)).utc();
            return emitDuration(a, XsdValues.formatDayTime(XsdValues.secondsBetween(second, first)));
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }

    @Override
    public boolean addYearMonthDurationToDateTime(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        return shiftDateTimeByMonths(a, yearMonth(2, a));
    }

    @Override
    public boolean subtractYearMonthDurationFromDateTime(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        return shiftDateTimeByMonths(a, -yearMonth(2, a));
    }

    private boolean shiftDateTimeByMonths(List<SWRLBuiltInArgument> a, long months) throws SWRLBuiltInException {
        try {
            XsdValues.DateTime source = XsdValues.parseDateTime(text(1, a));
            LocalDate shifted = source.value().toLocalDate().plusMonths(months);
            return emit(a, new XsdValues.DateTime(LocalDateTime.of(shifted, source.value().toLocalTime()), source.zone()).format(), "dateTime");
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new SWRLBuiltInException(e.getMessage());
        }
    }

    @Override
    public boolean addDayTimeDurationToTime(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        return shiftTime(a, dayTime(2, a));
    }

    @Override
    public boolean subtractDayTimeDurationFromTime(List<SWRLBuiltInArgument> a) throws SWRLBuiltInException {
        shape(a, 3);
        return shiftTime(a, dayTime(2, a).negate());
    }

    private boolean shiftTime(List<SWRLBuiltInArgument> a, BigDecimal seconds) throws SWRLBuiltInException {
        try {
            XsdValues.Time source = XsdValues.parseTime(text(1, a));
            return emit(a, new XsdValues.Time(XsdValues.wrap(XsdValues.seconds(source.time()).add(seconds)), source.zone()).format(), "time");
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        }
    }
}
