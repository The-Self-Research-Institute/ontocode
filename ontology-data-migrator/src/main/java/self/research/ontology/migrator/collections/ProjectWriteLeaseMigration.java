package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.time.OffsetDateTime;
import java.util.List;

public final class ProjectWriteLeaseMigration implements CollectionMigration {

    @Override
    public String name() {
        return "project_write_leases";
    }

    @Override
    public String sourceCollection() {
        return "project_write_leases";
    }

    @Override
    public List<Write> map(Document d) {
        OffsetDateTime acquired = Values.time(d, "acquiredAt");
        OffsetDateTime expires = Values.time(d, "expiresAt");
        if (acquired == null || expires == null) {
            throw new self.research.ontology.migrator.core.MappingException("Lease has no acquiredAt or expiresAt");
        }
        return List.of(Write.upsert("project_write_lease", "project_id")
                .put("project_id", Values.idText(d.get("_id")))
                .put("owner_token", Values.required(d, "ownerToken"))
                .put("holder", Values.text(d, "holder"))
                .put("acquired_at", acquired)
                .put("expires_at", expires)
                .build());
    }
}
