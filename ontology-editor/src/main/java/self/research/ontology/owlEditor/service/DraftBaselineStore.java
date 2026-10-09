package self.research.ontology.owlEditor.service;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@Component
public class DraftBaselineStore {

    private static final Logger log = LoggerFactory.getLogger(DraftBaselineStore.class);
    private static final String TYPE = "draft-baseline";

    private final GridFsTemplate gridFsTemplate;

    public DraftBaselineStore(GridFsTemplate gridFsTemplate) {
        this.gridFsTemplate = gridFsTemplate;
    }

    public void save(String projectId, String userId, String rdfXml) {
        Query byName = byName(projectId, userId);
        gridFsTemplate.delete(byName);
        gridFsTemplate.store(new ByteArrayInputStream(rdfXml.getBytes(StandardCharsets.UTF_8)),
                filename(projectId, userId), "application/rdf+xml",
                Map.of("type", TYPE, "projectId", projectId, "userId", userId));
    }

    public Optional<String> load(String projectId, String userId) {
        GridFSFile file = gridFsTemplate.findOne(byName(projectId, userId));
        if (file == null) {
            return Optional.empty();
        }
        GridFsResource resource = gridFsTemplate.getResource(file);
        try (InputStream in = resource.getInputStream()) {
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("[DRAFT-BASELINE] Could not read stored baseline for project {} user {}: {}",
                    projectId, userId, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String projectId, String userId) {
        gridFsTemplate.delete(byName(projectId, userId));
    }

    private static String filename(String projectId, String userId) {
        return TYPE + "/" + projectId + "/" + userId;
    }

    private static Query byName(String projectId, String userId) {
        return Query.query(Criteria.where("filename").is(filename(projectId, userId)));
    }
}
