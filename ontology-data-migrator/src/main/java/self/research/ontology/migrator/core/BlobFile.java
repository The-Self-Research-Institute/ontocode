package self.research.ontology.migrator.core;

import org.bson.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record BlobFile(String bucket, Object rawId, String id, String filename, long length, String contentType,
                       Instant uploadDate, Map<String, Object> metadata) {

    public static BlobFile fromFilesDocument(String bucket, Document d) {
        Object raw = d.get("_id");
        String id = Values.idText(raw);
        Map<String, Object> metadata = new LinkedHashMap<>();
        Object meta = d.get("metadata");
        if (meta instanceof Map<?, ?> m) {
            Object tree = Values.jsonTree(m);
            if (tree instanceof Map<?, ?> decoded) {
                decoded.forEach((k, v) -> metadata.put(String.valueOf(k), v));
            }
        }
        String contentType = Values.text(d, "contentType");
        if (contentType == null && metadata.get("_contentType") != null) {
            contentType = metadata.get("_contentType").toString();
        }
        String filename = Values.text(d, "filename");
        return new BlobFile(bucket, raw, id, filename == null || filename.isEmpty() ? id : filename,
                Values.longValue(d, "length", 0L), contentType, Values.instant(d.get("uploadDate")), metadata);
    }
}
