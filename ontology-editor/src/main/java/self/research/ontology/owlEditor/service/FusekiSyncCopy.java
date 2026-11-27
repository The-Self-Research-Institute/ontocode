package self.research.ontology.owlEditor.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class FusekiSyncCopy {

    static final String FILE_NAME = "fuseki-sync.copy";

    interface Upload {
        boolean run(Path copy, long bytes);
    }

    private FusekiSyncCopy() {
    }

    static boolean upload(Path projectDir, Path source, Upload upload) throws IOException {
        Path journal = projectDir.resolve("draft").resolve(DesktopEditJournal.JOURNAL);
        if (Files.exists(journal) && Files.size(journal) > 0) {
            throw new IllegalStateException("Recent edits are still being written into the draft; the triple store copy waits for them");
        }
        Path copy = projectDir.resolve(FILE_NAME);
        try {
            try (InputStream in = Files.newInputStream(source)) {
                Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
            }
            return upload.run(copy, Files.size(copy));
        } finally {
            Files.deleteIfExists(copy);
        }
    }
}
