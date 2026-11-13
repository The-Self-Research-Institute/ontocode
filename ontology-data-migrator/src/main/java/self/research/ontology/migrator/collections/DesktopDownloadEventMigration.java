package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class DesktopDownloadEventMigration implements CollectionMigration {

    @Override
    public String name() {
        return "desktop_download_events";
    }

    @Override
    public String sourceCollection() {
        return "desktop_download_events";
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("desktop_download_event", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("platform", Values.text(d, "platform"))
                .put("client_os", Values.text(d, "clientOs"))
                .put("version", Values.text(d, "version"))
                .put("filename", Values.text(d, "filename"))
                .put("ip_hash", Values.text(d, "ipHash"))
                .put("user_agent", Values.text(d, "userAgent"))
                .put("referer", Values.text(d, "referer"))
                .put("event_type", Values.text(d, "eventType"))
                .put("recorded_at", Values.time(d, "recordedAt"))
                .build());
    }
}
