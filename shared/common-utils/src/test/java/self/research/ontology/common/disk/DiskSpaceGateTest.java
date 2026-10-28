package self.research.ontology.common.disk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskSpaceGateTest {

    @TempDir
    Path dir;

    @Test
    void passesWhenNothingIsNeededAndNoReserveIsSet() {
        assertDoesNotThrow(() -> new DiskSpaceGate(0.0, 1.0).requireFree(dir, 0));
    }

    @Test
    void refusesWhenNeededBytesExceedUsableSpace() throws Exception {
        long usable = Files.getFileStore(dir).getUsableSpace();
        InsufficientDiskSpaceException e = assertThrows(InsufficientDiskSpaceException.class,
                () -> new DiskSpaceGate(0.0, 1.0).requireFree(dir, usable + 1024L * 1024 * 1024));
        assertEquals("The server disk is nearly full. Free some space or try again later.", e.getMessage());
        assertTrue(e.neededBytes() > e.usableBytes());
    }

    @Test
    void refusesWhenTheFreeFractionReserveCannotBeKept() {
        assertThrows(InsufficientDiskSpaceException.class, () -> new DiskSpaceGate(0.95, 1.0).requireFree(dir, 0)
                , "a 95 percent reserve should refuse on any disk that is more than 5 percent used");
    }

    @Test
    void multiplierScalesTheNeededBytes() throws Exception {
        long usable = Files.getFileStore(dir).getUsableSpace();
        long half = usable / 2 + 1024L * 1024;
        assertDoesNotThrow(() -> new DiskSpaceGate(0.0, 1.0).requireFree(dir, half));
        assertThrows(InsufficientDiskSpaceException.class, () -> new DiskSpaceGate(0.0, 3.0).requireFree(dir, half));
    }

    @Test
    void usesTheNearestExistingParentForAPathThatDoesNotExistYet() {
        assertDoesNotThrow(() -> new DiskSpaceGate(0.0, 1.0).requireFree(dir.resolve("a/b/c.owl"), 1));
    }

    @Test
    void hugeNeededBytesDoNotOverflow() {
        assertThrows(InsufficientDiskSpaceException.class,
                () -> new DiskSpaceGate(0.0, 3.0).requireFree(dir, Long.MAX_VALUE));
    }
}
