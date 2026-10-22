package self.research.ontology.reasoner.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

final class SnapshotTestSupport {

    static final String PIZZA = "PizzaTutorialWithDataV2 (1).owl";
    static final String INCONSISTENT = "inconsistent-ontology.owl";
    static final String CONSISTENT = "consistent-ontology.owl";

    private static final Map<String, byte[]> CONVERTED = new ConcurrentHashMap<>();

    private SnapshotTestSupport() {}

    static Path testData(String fileName) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test-data").resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("test-data/" + fileName + " not found above " + Path.of("").toAbsolutePath());
    }

    static OWLOntology load(OWLOntologyManager manager, String fileName) throws Exception {
        return manager.loadOntologyFromOntologyDocument(testData(fileName).toFile());
    }

    static byte[] nTriples(String fileName) {
        return nTriples(fileName, false);
    }

    static byte[] nTriples(String fileName, boolean withoutSwrl) {
        return CONVERTED.computeIfAbsent(fileName + "|" + withoutSwrl, name -> {
            try {
                OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                OWLOntology ontology = load(manager, fileName);
                if (withoutSwrl) {
                    manager.removeAxioms(ontology, ontology.getAxioms(AxiomType.SWRL_RULE));
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                manager.saveOntology(ontology, new NTriplesDocumentFormat(), out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new IllegalStateException("Could not convert " + name, e);
            }
        });
    }

    static final class FakeEditor {
        final EditorClient client = mock(EditorClient.class);
        final Map<String, byte[]> published = new ConcurrentHashMap<>();
        final Map<String, byte[]> drafts = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> revisions = new ConcurrentHashMap<>();
        final AtomicInteger loads = new AtomicInteger();

        FakeEditor() {
            when(client.getTripleCount(anyString())).thenAnswer(invocation -> {
                byte[] content = published.get(invocation.<String>getArgument(0));
                return content == null ? -1L : (long) lines(content);
            });
            when(client.getRevision(anyString())).thenAnswer(invocation ->
                    revisions.computeIfAbsent(invocation.getArgument(0), id -> new AtomicLong(1)).get());
            when(client.loadOntologyStream(anyString(), any(), any())).thenAnswer(invocation -> {
                loads.incrementAndGet();
                String projectId = invocation.getArgument(0);
                String userId = invocation.getArgument(1);
                byte[] content = userId != null && drafts.containsKey(userId) ? drafts.get(userId) : published.get(projectId);
                Function<InputStream, Object> reader = invocation.getArgument(2);
                return reader.apply(new ByteArrayInputStream(content));
            });
        }

        FakeEditor project(String projectId, String fileName) {
            published.put(projectId, nTriples(fileName));
            return this;
        }

        FakeEditor projectWithoutSwrl(String projectId, String fileName) {
            published.put(projectId, nTriples(fileName, true));
            return this;
        }

        FakeEditor draft(String userId, String fileName) {
            drafts.put(userId, nTriples(fileName));
            return this;
        }

        void bumpRevision(String projectId) {
            revisions.computeIfAbsent(projectId, id -> new AtomicLong(1)).incrementAndGet();
        }

        FakeEditor revision(String projectId, long revision) {
            revisions.computeIfAbsent(projectId, id -> new AtomicLong(1)).set(revision);
            return this;
        }

        long tripleCount(String projectId) {
            return lines(published.get(projectId));
        }

        private static int lines(byte[] content) {
            return (int) List.of(new String(content, StandardCharsets.UTF_8).split("\n")).stream()
                    .filter(line -> !line.isBlank())
                    .count();
        }
    }

    static OntologySessionService sessions(FakeEditor editor) {
        return sessions(editor, 500_000);
    }

    static OntologySessionService sessions(FakeEditor editor, long hermitMaxTriples) {
        OntologyCache cache = new OntologyCache();
        ReflectionTestUtils.setField(cache, "maxProjects", 20);
        return spy(new OntologySessionService(editor.client, cache, hermitMaxTriples, 120_000, 5_000_000));
    }

    static ReasoningSnapshotService snapshots(OntologySessionService sessions) {
        return snapshots(sessions, ReasoningSnapshotStore.disabled());
    }

    static ReasoningSnapshotService snapshots(OntologySessionService sessions, ReasoningSnapshotStore store) {
        return new ReasoningSnapshotService(sessions, store, 6, 120, 1.0);
    }
}
