package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeTempProjectSweeperTest {

    @TempDir
    Path root;

    private Path project(String name, long hoursOld) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Path file = Files.writeString(dir.resolve("ontology.current.owl"), "x");
        FileTime old = FileTime.from(Instant.now().minus(hoursOld, ChronoUnit.HOURS));
        Files.setLastModifiedTime(file, old);
        Files.setLastModifiedTime(dir, old);
        return dir;
    }

    @Test
    void removesOnlyExpiredMergeTempProjects() throws Exception {
        Path expired = project("merge-temp-aaa", 30);
        Path fresh = project("merge-temp-bbb", 2);
        Path oldRealProject = project("real-project", 500);
        int removed = MergeTempProjectSweeper.sweep(root, 24);
        assertEquals(1, removed);
        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(fresh));
        assertTrue(Files.exists(oldRealProject));
    }

    @Test
    void ttlIsConfigurable() throws Exception {
        Path threeHoursOld = project("merge-temp-ccc", 3);
        assertEquals(0, MergeTempProjectSweeper.sweep(root, 24));
        assertEquals(1, MergeTempProjectSweeper.sweep(root, 2));
        assertFalse(Files.exists(threeHoursOld));
    }

    @Test
    void discardRemovesOnlyTheNamedMergeTempProject() throws Exception {
        Path mine = project("merge-temp-ddd", 0);
        Path other = project("merge-temp-eee", 0);
        Path real = project("proj", 0);
        MergeTempProjectSweeper.discard(root, "merge-temp-ddd");
        MergeTempProjectSweeper.discard(root, "proj");
        assertFalse(Files.exists(mine));
        assertTrue(Files.exists(other));
        assertTrue(Files.exists(real));
    }

    @Test
    void aMissingRootIsHarmless() {
        assertEquals(0, MergeTempProjectSweeper.sweep(root.resolve("nope"), 24));
    }
}
