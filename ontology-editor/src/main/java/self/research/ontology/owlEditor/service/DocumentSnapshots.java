package self.research.ontology.owlEditor.service;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class DocumentSnapshots {

    private static final JsonWriterSettings EXACT = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();

    private final MongoTemplate mongoTemplate;

    public DocumentSnapshots(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public String snapshot(Object entity) {
        if (entity == null) {
            return null;
        }
        Document document = new Document();
        mongoTemplate.getConverter().write(entity, document);
        return document.toJson(EXACT);
    }

    public static String idOf(String json) {
        if (json == null) {
            return null;
        }
        Object id = Document.parse(json).get("_id");
        return id == null ? null : id.toString();
    }
}
