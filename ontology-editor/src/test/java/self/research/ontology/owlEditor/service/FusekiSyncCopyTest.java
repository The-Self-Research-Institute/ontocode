package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import self.research.ontology.owlEditor.util.AtomicFiles;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FusekiSyncCopyTest {

    @TempDir
    Path projectDir;

    @Test
    void theDraftCanBeReplacedWhileItsCopyIsBeingUploaded() throws Exception {
        Path draft = Files.createDirectories(projectDir.resolve("draft")).resolve("ontology.draft.owl");
        Files.writeString(draft, "edit 1");
        AtomicReference<String> uploaded = new AtomicReference<>();

        boolean done = FusekiSyncCopy.upload(projectDir, draft, (copy, bytes) -> {
            try (FileInputStream slowUpload = new FileInputStream(copy.toFile())) {
                Path staged = Files.createTempFile(draft.getParent(), "content-", ".staged");
                Files.writeString(staged, "edit 1 and edit 2");
                AtomicFiles.replace(staged, draft);
                uploaded.set(new String(slowUpload.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return true;
        });

        assertTrue(done);
        assertEquals("edit 1", uploaded.get());
        assertEquals("edit 1 and edit 2", Files.readString(draft));
        assertFalse(Files.exists(projectDir.resolve(FusekiSyncCopy.FILE_NAME)));
    }

    @Test
    void theUploadIsToldTheSizeOfTheCopy() throws Exception {
        Path current = Files.writeString(projectDir.resolve("ontology.current.owl"), "12345");
        AtomicReference<Long> size = new AtomicReference<>();

        FusekiSyncCopy.upload(projectDir, current, (copy, bytes) -> {
            size.set(bytes);
            return false;
        });

        assertEquals(5L, size.get());
        assertFalse(Files.exists(projectDir.resolve(FusekiSyncCopy.FILE_NAME)));
    }

    @Test
    void theUploadWaitsWhileRecentEditsAreOnlyInTheJournal() throws Exception {
        Path draft = Files.createDirectories(projectDir.resolve("draft")).resolve("ontology.draft.owl");
        Files.writeString(draft, "older draft");
        Files.writeString(draft.resolveSibling(DesktopEditJournal.JOURNAL), "1\tlineage\tcrc\tpayload\n");
        boolean[] uploaded = new boolean[1];

        assertThrows(IllegalStateException.class, () -> FusekiSyncCopy.upload(projectDir, draft, (copy, bytes) -> {
            uploaded[0] = true;
            return true;
        }));

        assertFalse(uploaded[0]);
    }

    @Test
    void theCopyIsRemovedWhenTheUploadFails() throws Exception {
        Path current = Files.writeString(projectDir.resolve("ontology.current.owl"), "content");

        assertThrows(IllegalStateException.class, () -> FusekiSyncCopy.upload(projectDir, current, (copy, bytes) -> {
            throw new IllegalStateException("triple store down");
        }));

        assertFalse(Files.exists(projectDir.resolve(FusekiSyncCopy.FILE_NAME)));
        assertEquals("content", Files.readString(current));
    }
}
