package self.research.ontology.owlEditor.migration;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProjectUserIdBackfill {

    private ProjectUserIdBackfill() {
    }

    public static int run(MongoOperations mongo) {
        Map<String, String> idByEmail = new HashMap<>();
        for (Document user : mongo.find(new Query(), Document.class, "users")) {
            Object email = user.get("email");
            Object id = user.get("_id");
            if (email != null && id != null) {
                idByEmail.put(email.toString().trim().toLowerCase(Locale.ROOT), id.toString());
            }
        }
        if (idByEmail.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (Document project : mongo.find(new Query(), Document.class, "projects")) {
            Update update = new Update();
            boolean changed = false;

            Object ownerEmail = project.get("ownerEmail");
            if (project.get("ownerId") == null && ownerEmail != null) {
                String id = idByEmail.get(ownerEmail.toString().trim().toLowerCase(Locale.ROOT));
                if (id != null) {
                    update.set("ownerId", id);
                    changed = true;
                }
            }

            Object rawMembers = project.get("members");
            if (rawMembers instanceof List<?> members) {
                List<Object> rewritten = new ArrayList<>();
                boolean membersChanged = false;
                for (Object member : members) {
                    if (member instanceof Document doc && doc.get("userId") == null && doc.get("email") != null) {
                        String id = idByEmail.get(doc.get("email").toString().trim().toLowerCase(Locale.ROOT));
                        if (id != null) {
                            Document copy = new Document(doc);
                            copy.put("userId", id);
                            rewritten.add(copy);
                            membersChanged = true;
                            continue;
                        }
                    }
                    rewritten.add(member);
                }
                if (membersChanged) {
                    update.set("members", rewritten);
                    changed = true;
                }
            }

            if (changed) {
                mongo.updateFirst(new Query(Criteria.where("_id").is(project.get("_id"))), update, "projects");
                updated++;
            }
        }
        return updated;
    }
}
