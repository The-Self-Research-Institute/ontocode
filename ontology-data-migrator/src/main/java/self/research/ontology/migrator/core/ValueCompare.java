package self.research.ontology.migrator.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Date;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public final class ValueCompare {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ValueCompare() {
    }

    public static boolean same(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        if (expected instanceof Write.Json json) {
            return sameJson(json.text(), actual.toString());
        }
        if (expected instanceof OffsetDateTime time) {
            return time.toInstant().equals(instantOf(actual));
        }
        if (expected instanceof Collection<?> list) {
            return actual instanceof Collection<?> other && sameList(List.copyOf(list), List.copyOf(other));
        }
        if (expected instanceof Number n && actual instanceof Number m) {
            return new BigDecimal(n.toString()).compareTo(new BigDecimal(m.toString())) == 0;
        }
        return expected.toString().equals(actual.toString());
    }

    private static boolean sameList(List<?> a, List<?> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!same(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static Instant instantOf(Object actual) {
        if (actual instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        if (actual instanceof Date t) {
            return t.toInstant();
        }
        if (actual instanceof Instant i) {
            return i;
        }
        return Instant.parse(actual.toString());
    }

    private static boolean sameJson(String expected, String actual) {
        try {
            JsonNode a = JSON.readTree(expected);
            JsonNode b = JSON.readTree(actual);
            return a.equals(b) || (a.isNumber() && b.isNumber() && a.decimalValue().compareTo(b.decimalValue()) == 0);
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
