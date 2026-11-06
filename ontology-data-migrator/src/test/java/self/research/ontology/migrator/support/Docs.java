package self.research.ontology.migrator.support;

import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.Date;

public final class Docs {

    private Docs() {
    }

    public static Date date(String iso) {
        return Date.from(Instant.parse(iso));
    }

    public static ObjectId oid(int n) {
        return new ObjectId(String.format("%024x", n));
    }

    public static Document user(int n, String email, String createdAt) {
        return new Document("_id", oid(n)).append("username", "user" + n).append("email", email)
                .append("createdAt", date(createdAt)).append("updatedAt", date(createdAt));
    }
}
