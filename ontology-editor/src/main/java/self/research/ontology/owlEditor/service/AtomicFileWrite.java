package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.util.AtomicFiles;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class AtomicFileWrite {

    interface Writer {
        void write(OutputStream out) throws IOException;
    }

    private AtomicFileWrite() {
    }

    static void writeString(Path target, String content) throws IOException {
        write(target, out -> out.write(content.getBytes(StandardCharsets.UTF_8)));
    }

    static void write(Path target, Writer writer) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                writer.write(out);
            }
            AtomicFiles.replace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
