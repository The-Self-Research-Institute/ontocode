package self.research.ontology.migrator.core;

import org.bson.Document;

import java.util.function.Function;

public record UniqueRule(String name, Function<Document, String> key) {

    public static UniqueRule of(String name, Function<Document, String> key) {
        return new UniqueRule(name, key);
    }

    public static UniqueRule field(String name, String field) {
        return new UniqueRule(name, d -> Values.text(d, field));
    }

    public static UniqueRule lowerField(String name, String field) {
        return new UniqueRule(name, d -> {
            String v = Values.text(d, field);
            return v == null ? null : v.toLowerCase(java.util.Locale.ROOT);
        });
    }

    public static UniqueRule pair(String name, String first, String second) {
        return new UniqueRule(name, d -> {
            String a = Values.text(d, first);
            String b = Values.text(d, second);
            return a == null || b == null ? null : a + "\u0000" + b;
        });
    }
}
