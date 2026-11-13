package self.research.ontology.migrator.mongo;

import org.bson.BsonType;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Filters.type;

public final class ResumeFilter {

    private static final List<BsonType> ORDER = List.of(BsonType.STRING, BsonType.DOCUMENT, BsonType.BINARY,
            BsonType.OBJECT_ID, BsonType.BOOLEAN, BsonType.DATE_TIME);

    private ResumeFilter() {
    }

    public static Bson after(Object afterId) {
        List<Bson> parts = new ArrayList<>();
        parts.add(gt("_id", afterId));
        for (BsonType later : typesAfter(afterId)) {
            parts.add(type("_id", later));
        }
        return or(parts);
    }

    public static List<BsonType> typesAfter(Object id) {
        int index;
        if (id instanceof Number) {
            index = -1;
        } else if (id instanceof String) {
            index = ORDER.indexOf(BsonType.STRING);
        } else if (id instanceof ObjectId) {
            index = ORDER.indexOf(BsonType.OBJECT_ID);
        } else {
            return List.of();
        }
        return ORDER.subList(index + 1, ORDER.size());
    }
}
