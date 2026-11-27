package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import self.research.ontology.owlEditor.util.AtomicFiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

@Service
@Conditional(FastOpenCondition.class)
public class DesktopEditJournal {

    private static final Logger log = LoggerFactory.getLogger(DesktopEditJournal.class);

    static final int MIN_AXIOMS = 10_000;
    static final String JOURNAL = "journal.log";
    static final String META = "draft.meta";

    private final StorageManager storage;
    private final int minAxioms;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastSeq = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Path> loadedFrom = new ConcurrentHashMap<>();

    @Autowired
    public DesktopEditJournal(StorageManager storage) {
        this(storage, MIN_AXIOMS);
    }

    DesktopEditJournal(StorageManager storage, int minAxioms) {
        this.storage = storage;
        this.minAxioms = minAxioms;
    }

    record Meta(String lineage, long seq) {
    }

    public void loadedFrom(String projectId, Path source) {
        loadedFrom.put(projectId, source);
    }

    public boolean append(String projectId, OWLOntology ontology, List<OWLOntologyChange> changes) throws IOException {
        if (ontology.getAxiomCount() < minAxioms || !journalable(changes)) {
            return false;
        }
        synchronized (lock(projectId)) {
            Optional<Meta> meta = ensureBase(projectId);
            if (meta.isEmpty()) {
                return false;
            }
            long seq = Math.max(lastSeq(projectId), meta.get().seq()) + 1;
            String payload = encode(changes, true) + "," + encode(changes, false);
            String line = seq + "\t" + meta.get().lineage() + "\t" + crc(payload) + "\t" + payload + "\n";
            try (FileChannel channel = FileChannel.open(journalPath(projectId), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            lastSeq.put(projectId, seq);
            return true;
        }
    }

    public boolean hasPending(String projectId) {
        try {
            Path journal = journalPath(projectId);
            return Files.exists(journal) && Files.size(journal) > 0;
        } catch (IOException e) {
            return true;
        }
    }

    public long lastSeq(String projectId) {
        synchronized (lock(projectId)) {
            Long known = lastSeq.get(projectId);
            if (known != null) {
                return known;
            }
            long found = readMeta(projectId).map(Meta::seq).orElse(0L);
            for (Entry entry : readEntries(projectId)) {
                found = Math.max(found, entry.seq());
            }
            lastSeq.put(projectId, found);
            return found;
        }
    }

    public void written(String projectId, long upTo) {
        synchronized (lock(projectId)) {
            try {
                String lineage = readMeta(projectId).map(Meta::lineage).orElse(UUID.randomUUID().toString());
                writeMeta(projectId, new Meta(lineage, upTo));
                List<String> keep = new ArrayList<>();
                for (Entry entry : readEntries(projectId)) {
                    if (entry.seq() > upTo && entry.lineage().equals(lineage)) {
                        keep.add(entry.line());
                    }
                }
                Path journal = journalPath(projectId);
                if (keep.isEmpty()) {
                    Files.deleteIfExists(journal);
                } else {
                    AtomicFiles.writeString(journal, String.join("\n", keep) + "\n");
                }
            } catch (IOException e) {
                log.warn("[Journal] Could not mark the draft of {} as written up to edit {}: {}", projectId, upTo, e.getMessage());
            }
        }
    }

    public int replay(String projectId, OWLOntology ontology, OWLOntologyManager manager) {
        synchronized (lock(projectId)) {
            Optional<Meta> meta = readMeta(projectId);
            List<Entry> entries = readEntries(projectId);
            if (entries.isEmpty()) {
                return 0;
            }
            if (meta.isEmpty() || !entries.get(0).lineage().equals(meta.get().lineage())) {
                setAside(projectId);
                return 0;
            }
            int applied = 0;
            for (Entry entry : entries) {
                if (entry.seq() <= meta.get().seq()) {
                    continue;
                }
                Optional<List<OWLOntologyChange>> changes = entry.changes(ontology);
                if (changes.isEmpty()) {
                    log.warn("[Journal] Stopped replaying {} at edit {}: the record is damaged", projectId, entry.seq());
                    break;
                }
                manager.applyChanges(changes.get());
                lastSeq.put(projectId, entry.seq());
                applied++;
            }
            log.info("[Journal] Replayed {} unsaved edit(s) of {} on top of its draft", applied, projectId);
            return applied;
        }
    }

    public void forget(String projectId) {
        lastSeq.remove(projectId);
    }

    public void setAside(String projectId) {
        synchronized (lock(projectId)) {
            Path journal = journalPath(projectId);
            try {
                if (Files.exists(journal)) {
                    Files.move(journal, journal.resolveSibling(JOURNAL + ".set-aside-" + System.currentTimeMillis()),
                            StandardCopyOption.ATOMIC_MOVE);
                    log.warn("[Journal] Set aside a journal of {} that does not belong to its draft", projectId);
                }
            } catch (IOException e) {
                log.warn("[Journal] Could not set aside the journal of {}: {}", projectId, e.getMessage());
            }
            lastSeq.remove(projectId);
        }
    }

    private Optional<Meta> ensureBase(String projectId) throws IOException {
        Path draft = storage.draftOntologyPath(projectId);
        Optional<Meta> meta = readMeta(projectId);
        if (Files.exists(draft)) {
            if (meta.isPresent()) {
                return meta;
            }
            Meta fresh = new Meta(UUID.randomUUID().toString(), lastSeq(projectId));
            writeMeta(projectId, fresh);
            return Optional.of(fresh);
        }
        Path source = loadedFrom.get(projectId);
        if (source == null || !Files.exists(source)) {
            return Optional.empty();
        }
        Files.createDirectories(draft.getParent());
        Path staged = Files.createTempFile(draft.getParent(), "base-", ".staged");
        try {
            try (InputStream in = Files.newInputStream(source)) {
                Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            AtomicFiles.replace(staged, draft);
        } finally {
            Files.deleteIfExists(staged);
        }
        setAside(projectId);
        Meta fresh = new Meta(UUID.randomUUID().toString(), 0);
        writeMeta(projectId, fresh);
        lastSeq.put(projectId, 0L);
        return Optional.of(fresh);
    }

    private static boolean journalable(List<OWLOntologyChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return false;
        }
        for (OWLOntologyChange change : changes) {
            if (!(change instanceof AddAxiom || change instanceof RemoveAxiom)
                    || change.getAxiom().anonymousIndividuals().findAny().isPresent()) {
                return false;
            }
        }
        return true;
    }

    private static String encode(List<OWLOntologyChange> changes, boolean removed) throws IOException {
        List<OWLAxiom> axioms = new ArrayList<>();
        for (OWLOntologyChange change : changes) {
            if (removed == change instanceof RemoveAxiom) {
                axioms.add(change.getAxiom());
            }
        }
        return axioms.isEmpty() ? "" : Base64.getEncoder().encodeToString(render(axioms).getBytes(StandardCharsets.UTF_8));
    }

    static String render(Collection<OWLAxiom> axioms) throws IOException {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology holder = manager.createOntology();
            manager.addAxioms(holder, axioms.stream());
            StringDocumentTarget target = new StringDocumentTarget();
            FunctionalSyntaxDocumentFormat format = new FunctionalSyntaxDocumentFormat();
            format.setAddMissingTypes(false);
            manager.saveOntology(holder, format, target);
            return target.toString();
        } catch (Exception e) {
            throw new IOException("Could not write the change to the journal: " + e.getMessage(), e);
        }
    }

    static List<OWLAxiom> parse(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.setOntologyLoaderConfiguration(new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        return manager.loadOntologyFromOntologyDocument(new StringDocumentSource(document)).axioms().toList();
    }

    private static String crc(String payload) {
        CRC32 crc = new CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    private List<Entry> readEntries(String projectId) {
        Path journal = journalPath(projectId);
        List<Entry> entries = new ArrayList<>();
        if (!Files.exists(journal)) {
            return entries;
        }
        try {
            for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
                Optional<Entry> entry = Entry.of(line);
                if (entry.isEmpty()) {
                    break;
                }
                entries.add(entry.get());
            }
        } catch (IOException e) {
            log.warn("[Journal] Could not read the journal of {}: {}", projectId, e.getMessage());
        }
        return entries;
    }

    private Optional<Meta> readMeta(String projectId) {
        Path file = storage.draftDir(projectId).resolve(META);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            Properties properties = new Properties();
            properties.load(in);
            return Optional.of(new Meta(properties.getProperty("lineage"), Long.parseLong(properties.getProperty("seq"))));
        } catch (IOException | RuntimeException e) {
            log.warn("[Journal] Could not read the draft marker of {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    private void writeMeta(String projectId, Meta meta) throws IOException {
        Path dir = storage.draftDir(projectId);
        Files.createDirectories(dir);
        AtomicFiles.writeString(dir.resolve(META), "lineage=" + meta.lineage() + "\nseq=" + meta.seq() + "\n");
    }

    private Path journalPath(String projectId) {
        return storage.draftDir(projectId).resolve(JOURNAL);
    }

    private Object lock(String projectId) {
        return locks.computeIfAbsent(projectId, id -> new Object());
    }

    record Entry(long seq, String lineage, String payload, String line) {

        static Optional<Entry> of(String line) {
            String[] parts = line.split("\t", 4);
            if (parts.length != 4 || !crc(parts[3]).equals(parts[2])) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Entry(Long.parseLong(parts[0]), parts[1], parts[3], line));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        Optional<List<OWLOntologyChange>> changes(OWLOntology ontology) {
            String[] halves = payload.split(",", -1);
            if (halves.length != 2) {
                return Optional.empty();
            }
            try {
                List<OWLOntologyChange> changes = new ArrayList<>();
                for (OWLAxiom axiom : decode(halves[0])) {
                    changes.add(new RemoveAxiom(ontology, axiom));
                }
                for (OWLAxiom axiom : decode(halves[1])) {
                    changes.add(new AddAxiom(ontology, axiom));
                }
                return Optional.of(changes);
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        private static List<OWLAxiom> decode(String half) throws Exception {
            if (half.isEmpty()) {
                return List.of();
            }
            return parse(new String(Base64.getDecoder().decode(half), StandardCharsets.UTF_8));
        }
    }
}
