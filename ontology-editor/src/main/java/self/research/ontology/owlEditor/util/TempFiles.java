package self.research.ontology.owlEditor.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TempFiles {

    private static final Logger log = LoggerFactory.getLogger(TempFiles.class);

    private TempFiles() {
    }

    public static Path saveUpload(MultipartFile upload, String prefix, String suffix) throws IOException {
        return saveUpload(upload, null, prefix, suffix);
    }

    public static Path saveUpload(MultipartFile upload, Path directory, String prefix, String suffix) throws IOException {
        Path file;
        if (directory == null) {
            file = Files.createTempFile(prefix, suffix);
        } else {
            Files.createDirectories(directory);
            file = Files.createTempFile(directory, prefix, suffix);
        }
        try {
            upload.transferTo(file.toFile());
        } catch (IOException | RuntimeException e) {
            discard(file);
            throw e;
        }
        return file;
    }

    public static void discard(Path file) {
        if (file == null) {
            return;
        }
        try {
            if (!Files.deleteIfExists(file)) {
                file.toFile().deleteOnExit();
            }
        } catch (IOException | RuntimeException e) {
            file.toFile().deleteOnExit();
            log.debug("Temp file {} is locked, it will be removed on exit", file);
        }
    }
}
