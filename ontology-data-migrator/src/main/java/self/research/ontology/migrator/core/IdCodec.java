package self.research.ontology.migrator.core;

import org.bson.types.ObjectId;

public final class IdCodec {

    private IdCodec() {
    }

    public static String encode(Object id) {
        if (id instanceof ObjectId oid) {
            return "oid:" + oid.toHexString();
        }
        if (id instanceof String s) {
            return "str:" + s;
        }
        if (id instanceof Integer i) {
            return "int:" + i;
        }
        if (id instanceof Long l) {
            return "long:" + l;
        }
        return null;
    }

    public static Object decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        int colon = encoded.indexOf(':');
        String kind = encoded.substring(0, colon);
        String value = encoded.substring(colon + 1);
        return switch (kind) {
            case "oid" -> new ObjectId(value);
            case "str" -> value;
            case "int" -> Integer.valueOf(value);
            case "long" -> Long.valueOf(value);
            default -> throw new IllegalArgumentException("Unknown checkpoint id kind " + kind);
        };
    }

    public static String kind(Object id) {
        String e = encode(id);
        return e == null ? "other" : e.substring(0, e.indexOf(':'));
    }
}
