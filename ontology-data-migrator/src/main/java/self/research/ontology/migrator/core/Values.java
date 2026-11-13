package self.research.ontology.migrator.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Values {

    public static final char FULL_WIDTH_DOT = '．';

    private static final ObjectMapper JSON = new ObjectMapper();

    private Values() {
    }

    public static String idText(Object id) {
        if (id == null) {
            return null;
        }
        if (id instanceof ObjectId oid) {
            return oid.toHexString();
        }
        return id.toString();
    }

    public static String text(Document d, String key) {
        Object v = d.get(key);
        return v == null ? null : idText(v);
    }

    public static String required(Document d, String key) {
        String v = text(d, key);
        if (v == null || v.isEmpty()) {
            throw new MappingException("Missing required field " + key);
        }
        return v;
    }

    public static Instant instant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Date date) {
            return date.toInstant();
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof LocalDateTime l) {
            return l.toInstant(ZoneOffset.UTC);
        }
        if (v instanceof LocalDate l) {
            return l.atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if (v instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        if (v instanceof ZonedDateTime z) {
            return z.toInstant();
        }
        if (v instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        if (v instanceof String s) {
            return parseInstant(s.trim());
        }
        throw new MappingException("Cannot read a date from " + v.getClass().getSimpleName());
    }

    private static Instant parseInstant(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new MappingException("Cannot read a date from text " + s);
        }
    }

    public static OffsetDateTime time(Document d, String key) {
        return time(d.get(key));
    }

    public static OffsetDateTime time(Object v) {
        Instant i = instant(v);
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    public static Boolean bool(Document d, String key) {
        Object v = d.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString());
    }

    public static boolean bool(Document d, String key, boolean whenMissing) {
        Boolean v = bool(d, key);
        return v == null ? whenMissing : v;
    }

    public static Integer integer(Document d, String key) {
        Long v = longValue(d, key);
        return v == null ? null : Math.toIntExact(v);
    }

    public static int integer(Document d, String key, int whenMissing) {
        Integer v = integer(d, key);
        return v == null ? whenMissing : v;
    }

    public static Long longValue(Document d, String key) {
        Object v = d.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new MappingException("Field " + key + " is not a number");
        }
    }

    public static long longValue(Document d, String key, long whenMissing) {
        Long v = longValue(d, key);
        return v == null ? whenMissing : v;
    }

    public static Double decimal(Document d, String key) {
        Object v = d.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return Double.valueOf(v.toString());
    }

    public static List<String> list(Document d, String key) {
        Object v = d.get(key);
        if (v == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null) {
                    out.add(idText(o));
                }
            }
        } else {
            out.add(idText(v));
        }
        return out;
    }

    public static List<String> listOrEmpty(Document d, String key) {
        List<String> v = list(d, key);
        return v == null ? new ArrayList<>() : v;
    }

    public static List<Document> documents(Document d, String key) {
        Object v = d.get(key);
        List<Document> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o instanceof Document doc) {
                    out.add(doc);
                } else if (o instanceof Map<?, ?> m) {
                    out.add(new Document(castMap(m)));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    public static Write.Json json(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new Write.Json(JSON.writeValueAsString(jsonTree(v)));
        } catch (JsonProcessingException e) {
            throw new MappingException("Cannot write JSON: " + e.getOriginalMessage());
        }
    }

    public static Object jsonTree(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            return v;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(decodeKey(String.valueOf(e.getKey())), jsonTree(e.getValue()));
            }
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object o : c) {
                out.add(jsonTree(o));
            }
            return out;
        }
        if (v instanceof ObjectId oid) {
            return oid.toHexString();
        }
        if (v instanceof Decimal128 d) {
            return d.bigDecimalValue();
        }
        if (v instanceof Number) {
            return v;
        }
        if (v instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (v instanceof org.bson.types.Binary b) {
            return Base64.getEncoder().encodeToString(b.getData());
        }
        if (v instanceof Date || v instanceof Instant || v instanceof LocalDateTime || v instanceof OffsetDateTime
                || v instanceof ZonedDateTime || v instanceof LocalDate) {
            return instant(v).toString();
        }
        return v.toString();
    }

    public static String decodeKey(String key) {
        return key.replace(FULL_WIDTH_DOT, '.');
    }

    public static Instant recency(Document d, String... dateFields) {
        Instant best = null;
        for (String field : dateFields) {
            Object v = d.get(field);
            if (v != null) {
                Instant i = instant(v);
                if (i != null && (best == null || i.isAfter(best))) {
                    best = i;
                }
            }
        }
        if (best == null && d.get("_id") instanceof ObjectId oid) {
            best = oid.getDate().toInstant();
        }
        return best == null ? Instant.EPOCH : best;
    }
}
