package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ProjectStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
public class ProjectMetadataService {

    private final ObjectMapper mapper;
    private final Path projectsRoot;

    public ProjectMetadataService(ObjectMapper mapper,
                                  @Value("${ontocode.data.dir:./data}") String rootDir) {
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.projectsRoot = Path.of(rootDir).toAbsolutePath().normalize().resolve("projects");
    }


    public Optional<ProjectStatus> readStatus(String projectId) {
        Path statusFile = statusPath(projectId);
        if (!Files.exists(statusFile)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(statusFile)) {
            return Optional.of(mapper.readValue(in, ProjectStatus.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeStatus(String projectId, ProjectStatus status) {
        writeJson(statusPath(projectId), status);
    }

    public Optional<Map<String, Object>> readMeta(String projectId) {
        Path meta = metaPath(projectId);
        if (!Files.exists(meta)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(meta)) {
            return Optional.of(mapper.readValue(in, new TypeReference<>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeMeta(String projectId, Map<String, Object> meta) {
        meta.put("lastUpdated", Instant.now().toString());
        writeJson(metaPath(projectId), meta);
    }

    private void writeJson(Path path, Object payload) {
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                mapper.writeValue(out, payload);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path statusPath(String projectId) {
        return projectsRoot.resolve(projectId).resolve("status.json");
    }

    private Path metaPath(String projectId) {
        return projectsRoot.resolve(projectId).resolve("meta.json");
    }
}