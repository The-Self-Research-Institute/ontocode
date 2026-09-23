package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
public class LineRangeSpliceWriter {

    public record SpliceEdit(long startLine, int lineCount, String newText) {}

    public Path splice(Path sourceFile, String tempFileExtension, List<SpliceEdit> edits) throws IOException {
        Path outputFile = Files.createTempFile("codeview-apply-", "." + tempFileExtension);
        try (BufferedReader reader = Files.newBufferedReader(sourceFile, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            long index = 0;
            int editPos = 0;
            long skipUntil = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                if (editPos < edits.size() && index == edits.get(editPos).startLine()) {
                    SpliceEdit edit = edits.get(editPos);
                    writeText(writer, edit.newText());
                    skipUntil = edit.startLine() + edit.lineCount();
                    editPos++;
                }
                if (skipUntil <= index) {
                    writer.write(line);
                    writer.write("\n");
                }
                index++;
            }
            while (editPos < edits.size()) {
                writeText(writer, edits.get(editPos).newText());
                editPos++;
            }
        }
        return outputFile;
    }

    private void writeText(BufferedWriter writer, String text) throws IOException {
        if (text.isEmpty()) {
            return;
        }
        for (String part : text.split("\n", -1)) {
            writer.write(part);
            writer.write("\n");
        }
    }
}
