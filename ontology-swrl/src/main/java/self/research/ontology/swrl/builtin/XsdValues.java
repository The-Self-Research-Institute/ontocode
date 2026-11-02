package self.research.ontology.swrl.builtin;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XsdValues {

    private static final Pattern DATE = Pattern.compile("^(-?\\d{4,})-(\\d{2})-(\\d{2})(Z|[+-]\\d{2}:\\d{2})?$");
    private static final Pattern TIME = Pattern.compile("^(\\d{2}):(\\d{2}):(\\d{2})(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$");
    private static final Pattern DATE_TIME = Pattern.compile("^(-?\\d{4,}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)(Z|[+-]\\d{2}:\\d{2})?$");
    private static final Pattern DURATION = Pattern.compile(
            "^(-)?P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$");
    private static final BigDecimal DAY = BigDecimal.valueOf(86400);
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private XsdValues() {
    }

    public record Date(LocalDate date, String zone) {
        public LocalDateTime utcStart() {
            return date.atStartOfDay().minusSeconds(offsetSeconds(zone));
        }

        public String format() {
            return formatDate(date) + (zone == null ? "" : zone);
        }
    }

    public record Time(LocalTime time, String zone) {
        public BigDecimal utcSeconds() {
            return seconds(time).subtract(BigDecimal.valueOf(offsetSeconds(zone)));
        }

        public String format() {
            return formatTime(time) + (zone == null ? "" : zone);
        }
    }

    public record DateTime(LocalDateTime value, String zone) {
        public LocalDateTime utc() {
            return value.minusSeconds(offsetSeconds(zone));
        }

        public String format() {
            return formatDate(value.toLocalDate()) + "T" + formatTime(value.toLocalTime()) + (zone == null ? "" : zone);
        }
    }

    public record Duration(long months, BigDecimal seconds) {
        public boolean hasMonths() {
            return months != 0;
        }

        public boolean hasSeconds() {
            return seconds.signum() != 0;
        }
    }

    public static Date parseDate(String text) {
        Matcher m = DATE.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(text + " is not a date in the form 2024-03-15");
        }
        try {
            return new Date(LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))), checkedZone(m.group(4)));
        } catch (DateTimeException | NumberFormatException e) {
            throw new IllegalArgumentException(text + " is not a real date");
        }
    }

    public static Time parseTime(String text) {
        Matcher m = TIME.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(text + " is not a time in the form 10:30:00");
        }
        try {
            int nano = m.group(4) == null ? 0 : new BigDecimal(m.group(4)).movePointRight(9).setScale(0, RoundingMode.DOWN).intValueExact();
            return new Time(LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), nano), checkedZone(m.group(5)));
        } catch (DateTimeException | ArithmeticException e) {
            throw new IllegalArgumentException(text + " is not a real time");
        }
    }

    public static DateTime parseDateTime(String text) {
        Matcher m = DATE_TIME.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(text + " is not a date and time in the form 2024-03-15T14:30:00");
        }
        Date date = parseDate(m.group(1));
        Time time = parseTime(m.group(2));
        return new DateTime(LocalDateTime.of(date.date(), time.time()), checkedZone(m.group(3)));
    }

    public static DateTime parseDateOrDateTime(String text) {
        String trimmed = text.trim();
        if (trimmed.indexOf('T') >= 0) {
            return parseDateTime(trimmed);
        }
        Date date = parseDate(trimmed);
        return new DateTime(date.date().atStartOfDay(), date.zone());
    }

    public static Duration parseDuration(String text) {
        String trimmed = text.trim();
        Matcher m = DURATION.matcher(trimmed);
        if (!m.matches() || trimmed.endsWith("P") || trimmed.endsWith("T")) {
            throw new IllegalArgumentException(text + " is not a duration such as P1Y2M or P1DT2H");
        }
        long months = (m.group(2) == null ? 0 : Long.parseLong(m.group(2)) * 12) + (m.group(3) == null ? 0 : Long.parseLong(m.group(3)));
        BigDecimal seconds = BigDecimal.ZERO;
        if (m.group(4) != null) {
            seconds = seconds.add(new BigDecimal(m.group(4)).multiply(DAY));
        }
        if (m.group(5) != null) {
            seconds = seconds.add(new BigDecimal(m.group(5)).multiply(BigDecimal.valueOf(3600)));
        }
        if (m.group(6) != null) {
            seconds = seconds.add(new BigDecimal(m.group(6)).multiply(BigDecimal.valueOf(60)));
        }
        if (m.group(7) != null) {
            seconds = seconds.add(new BigDecimal(m.group(7)));
        }
        if (m.group(1) != null) {
            months = -months;
            seconds = seconds.negate();
        }
        return new Duration(months, seconds);
    }

    public static String formatYearMonth(long months) {
        if (months == 0) {
            return "P0M";
        }
        long abs = Math.abs(months);
        long years = abs / 12;
        long rest = abs % 12;
        return (months < 0 ? "-" : "") + "P" + (years > 0 ? years + "Y" : "") + (rest > 0 ? rest + "M" : "");
    }

    public static String formatDayTime(BigDecimal totalSeconds) {
        if (totalSeconds.signum() == 0) {
            return "PT0S";
        }
        BigDecimal abs = totalSeconds.abs();
        BigInteger whole = abs.toBigInteger();
        BigDecimal fraction = abs.subtract(new BigDecimal(whole));
        BigInteger days = whole.divide(BigInteger.valueOf(86400));
        long rest = whole.mod(BigInteger.valueOf(86400)).longValue();
        long hours = rest / 3600;
        long minutes = rest % 3600 / 60;
        BigDecimal seconds = BigDecimal.valueOf(rest % 60).add(fraction);
        StringBuilder out = new StringBuilder(totalSeconds.signum() < 0 ? "-P" : "P");
        if (days.signum() > 0) {
            out.append(days).append('D');
        }
        if (hours > 0 || minutes > 0 || seconds.signum() > 0) {
            out.append('T');
            if (hours > 0) {
                out.append(hours).append('H');
            }
            if (minutes > 0) {
                out.append(minutes).append('M');
            }
            if (seconds.signum() > 0) {
                out.append(plain(seconds)).append('S');
            }
        }
        return out.toString();
    }

    public static String formatDate(LocalDate date) {
        return String.format("%s%04d-%02d-%02d", date.getYear() < 0 ? "-" : "", Math.abs(date.getYear()), date.getMonthValue(), date.getDayOfMonth());
    }

    public static String formatTime(LocalTime time) {
        String base = String.format("%02d:%02d:%02d", time.getHour(), time.getMinute(), time.getSecond());
        if (time.getNano() == 0) {
            return base;
        }
        return base + "." + String.format("%09d", time.getNano()).replaceAll("0+$", "");
    }

    public static String zoneFromArgument(String text) {
        String zone = text == null ? "" : text.trim();
        if (zone.isEmpty()) {
            return null;
        }
        if (zone.equalsIgnoreCase("Z") || zone.equalsIgnoreCase("GMT") || zone.equalsIgnoreCase("UTC")) {
            return "Z";
        }
        if (zone.matches("[+-]\\d{2}:\\d{2}")) {
            return checkedZone(zone);
        }
        throw new IllegalArgumentException(text + " is not a time zone such as Z, +05:30 or -08:00");
    }

    public static Date buildDate(long year, long month, long day, String zone) {
        try {
            if (Math.abs(year) > 9999) {
                throw new DateTimeException("year");
            }
            return new Date(LocalDate.of((int) year, (int) month, (int) day), zoneFromArgument(zone));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(year + "-" + month + "-" + day + " is not a real date");
        }
    }

    public static Time buildTime(long hour, long minute, BigDecimal second, String zone) {
        try {
            if (second.signum() < 0 || second.compareTo(BigDecimal.valueOf(60)) >= 0) {
                throw new DateTimeException("second");
            }
            int nano = second.subtract(new BigDecimal(second.toBigInteger())).movePointRight(9).setScale(0, RoundingMode.DOWN).intValueExact();
            return new Time(LocalTime.of((int) hour, (int) minute, second.intValue(), nano), zoneFromArgument(zone));
        } catch (DateTimeException | ArithmeticException e) {
            throw new IllegalArgumentException(hour + ":" + minute + ":" + second.toPlainString() + " is not a real time");
        }
    }

    public static DateTime buildDateTime(Date date, Time time) {
        return new DateTime(LocalDateTime.of(date.date(), time.time()), time.zone());
    }

    public static BigDecimal seconds(LocalTime time) {
        return BigDecimal.valueOf(time.toSecondOfDay()).add(BigDecimal.valueOf(time.getNano(), 9));
    }

    public static LocalTime wrap(BigDecimal secondsOfDay) {
        BigDecimal normal = secondsOfDay.remainder(DAY);
        if (normal.signum() < 0) {
            normal = normal.add(DAY);
        }
        BigInteger whole = normal.toBigInteger();
        int nano = normal.subtract(new BigDecimal(whole)).movePointRight(9).setScale(0, RoundingMode.DOWN).intValue();
        return LocalTime.ofSecondOfDay(whole.longValue()).withNano(nano);
    }

    public static BigDecimal secondsBetween(LocalDateTime from, LocalDateTime to) {
        java.time.Duration between = java.time.Duration.between(from, to);
        return BigDecimal.valueOf(between.getSeconds()).add(BigDecimal.valueOf(between.getNano(), 9));
    }

    public static BigDecimal roundHalfUp(BigDecimal value) {
        return value.add(HALF).setScale(0, RoundingMode.FLOOR);
    }

    public static BigDecimal scaleSeconds(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    private static String checkedZone(String zone) {
        if (zone == null) {
            return null;
        }
        if (zone.equals("Z")) {
            return zone;
        }
        int hours = Integer.parseInt(zone.substring(1, 3));
        int minutes = Integer.parseInt(zone.substring(4, 6));
        if (hours > 14 || minutes > 59 || (hours == 14 && minutes > 0)) {
            throw new IllegalArgumentException(zone + " is not a valid time zone");
        }
        return zone.equals("+00:00") || zone.equals("-00:00") ? "Z" : zone;
    }

    private static int offsetSeconds(String zone) {
        if (zone == null || zone.equals("Z")) {
            return 0;
        }
        int total = Integer.parseInt(zone.substring(1, 3)) * 3600 + Integer.parseInt(zone.substring(4, 6)) * 60;
        return zone.charAt(0) == '-' ? -total : total;
    }
}
