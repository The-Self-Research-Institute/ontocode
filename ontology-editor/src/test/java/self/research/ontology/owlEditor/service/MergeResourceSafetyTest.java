package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.common.admission.Estimates;
import self.research.ontology.common.admission.RefusalReason;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.common.admission.WorkKind;
import self.research.ontology.common.disk.DiskSpaceGate;
import self.research.ontology.common.disk.InsufficientDiskSpaceException;
import self.research.ontology.owlEditor.model.merge.MergeOptions;
import self.research.ontology.owlEditor.model.merge.MergeStrategy;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MergeResourceSafetyTest {

    private static final String NS = "http://ex.org/";

    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private ProjectImportService importService;
    @Mock
    private StorageManager storageManager;
    @Mock
    private ProjectMetadataService metadataService;
    @Mock
    private OntologyHistoryService historyService;

    private OntologyMergeService service;
    private ReservationLedger ledger;
    private Path targetFile;
    private Path sourceFile;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new OntologyMergeService(datasetService, importService, storageManager, metadataService, historyService);
        ledger = new FakeMemorySignals().ledger();
        service.admission = new HeavyWorkAdmission(ledger, new Estimates());
        targetFile = dir.resolve("target.owl");
        sourceFile = dir.resolve("source.owl");
        write(targetFile, "target");
        write(sourceFile, "source");
        when(storageManager.findCurrentOntology("proj")).thenReturn(Optional.of(targetFile));
        when(storageManager.projectsRoot()).thenReturn(dir.resolve("projects"));
        when(importService.trySubmitImport(anyString(), any())).thenReturn(true);
    }

    private static void write(Path path, String name) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + name));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + name + "Only"))));
        try (FileOutputStream out = new FileOutputStream(path.toFile())) {
            manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        }
    }

    private MergeOptions union() {
        MergeOptions options = new MergeOptions();
        options.setStrategy(MergeStrategy.SIMPLE_UNION);
        return options;
    }

    @Test
    void admissionIsReleasedAfterASuccessfulMerge() throws Exception {
        service.mergeOntologies("src", sourceFile, "proj", null, null, union(), "u1", "User");
        assertEquals(0, ledger.reservedBytes());
        assertTrue(Files.readString(targetFile).contains("sourceOnly"));
    }

    @Test
    void admissionIsReleasedWhenTheMergeFails() {
        Path missing = dir.resolve("missing.owl");
        assertThrows(Exception.class,
                () -> service.mergeOntologies("src", missing, "proj", null, null, union(), "u1", "User"));
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void admissionIsReleasedAfterAnAnalysis() throws Exception {
        service.analyzeOntologies("src", sourceFile, "proj", null, "u1");
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void aRefusedAdmissionLeavesTheStoredFileUntouched() throws Exception {
        HeavyWorkAdmission refusing = mock(HeavyWorkAdmission.class);
        when(refusing.estimates()).thenReturn(new Estimates());
        when(refusing.admit(anyString(), eq(WorkKind.IMPORT_PARSE), anyLong()))
                .thenThrow(new BusyException(RefusalReason.OVER_CAPACITY, "The server is busy.", 5000));
        service.admission = refusing;
        String before = Files.readString(targetFile);
        assertThrows(BusyException.class,
                () -> service.mergeOntologies("src", sourceFile, "proj", null, null, union(), "u1", "User"));
        assertEquals(before, Files.readString(targetFile));
        verify(importService, never()).trySubmitImport(anyString(), any());
    }

    @Test
    void theEstimateComesFromTheFileSizes() throws Exception {
        HeavyWorkAdmission recording = mock(HeavyWorkAdmission.class);
        when(recording.estimates()).thenReturn(new Estimates());
        when(recording.admit(anyString(), any(), anyLong())).thenReturn(HeavyWorkAdmission.Hold.NONE);
        service.admission = recording;
        service.analyzeOntologies("src", sourceFile, "proj", null, "u1");
        long expected = new Estimates().importParse(2 * Files.size(sourceFile) + Files.size(targetFile));
        verify(recording).admit("u1", WorkKind.IMPORT_PARSE, expected);
    }

    @Test
    void anInputOverTheLimitIsRefusedWithAPlainMessage() throws Exception {
        Path big = dir.resolve("big.owl");
        try (RandomAccessFile file = new RandomAccessFile(big.toFile(), "rw")) {
            file.setLength(Limit.MERGE_INPUT_MAX_MB.mb() + 1);
        }
        MergeInputTooLargeException e = assertThrows(MergeInputTooLargeException.class,
                () -> service.mergeOntologies("src", big, "proj", null, null, union(), "u1", "User"));
        assertTrue(e.getMessage().contains("Split it into smaller files"));
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void aFullDiskRefusesBeforeTheStoredFileIsTouched() throws Exception {
        service.diskGate = new DiskSpaceGate(0.99, 1.0);
        String before = Files.readString(targetFile);
        assertThrows(InsufficientDiskSpaceException.class,
                () -> service.mergeOntologies("src", sourceFile, "proj", null, null, union(), "u1", "User"));
        assertEquals(before, Files.readString(targetFile));
        assertEquals(0, ledger.reservedBytes());
        try (Stream<Path> files = Files.list(dir)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void aFailedWriteKeepsTheOldFileAndLeavesNoTemporaryFile() throws Exception {
        Path stored = dir.resolve("stored.owl");
        Files.writeString(stored, "old content");
        assertThrows(IOException.class, () -> AtomicFileWrite.write(stored, out -> {
            out.write("half of the new con".getBytes());
            throw new IOException("disk error");
        }));
        assertEquals("old content", Files.readString(stored));
        try (Stream<Path> files = Files.list(dir)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void aFailedSaveAsNewFileMergeRemovesItsTemporaryProject() throws Exception {
        Path projects = dir.resolve("projects");
        when(storageManager.prepareProjectDir(anyString())).thenAnswer(inv ->
                Files.createDirectories(projects.resolve(inv.<String>getArgument(0))));
        when(storageManager.projectDir(anyString())).thenAnswer(inv -> projects.resolve(inv.<String>getArgument(0)));
        when(storageManager.resolveProjectFile(anyString(), eq("ontology.current.owl"))).thenAnswer(inv ->
                projects.resolve(inv.<String>getArgument(0)).resolve("blocked").resolve("ontology.current.owl"));
        assertThrows(Exception.class,
                () -> service.mergeOntologies("src", sourceFile, "proj", null, "merged.owl", union(), "u1", "User"));
        try (Stream<Path> children = Files.list(projects)) {
            assertEquals(0, children.filter(p -> p.getFileName().toString().startsWith("merge-temp-")).count());
        }
        assertEquals(0, ledger.reservedBytes());
    }
}
